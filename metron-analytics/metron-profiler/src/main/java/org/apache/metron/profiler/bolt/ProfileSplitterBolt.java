/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.metron.profiler.bolt;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import org.apache.commons.lang.StringUtils;
import org.apache.metron.common.bolt.ConfiguredProfilerBolt;
import org.apache.metron.common.configuration.profiler.ProfileConfig;
import org.apache.metron.common.configuration.profiler.ProfilerConfig;
import org.apache.metron.common.dsl.Context;
import org.apache.metron.common.dsl.StellarFunctions;
import org.apache.metron.profiler.stellar.StellarExecutor;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.Map;

import static java.lang.String.format;

/**
 * The bolt responsible for filtering incoming messages and directing
 * each to the one or more bolts responsible for building a Profile.  Each
 * message may be needed by 0, 1 or even many Profiles.
 */
public class ProfileSplitterBolt extends ConfiguredProfilerBolt {

  protected static final Logger LOG = LoggerFactory.getLogger(ProfileSplitterBolt.class);

  private OutputCollector collector;

  /**
   * JSON parser.
   */
  private transient JSONParser parser;

  /**
   * Executes Stellar code.
   */
  private StellarExecutor executor;

  /**
   * @param zookeeperUrl The Zookeeper URL that contains the configuration for this bolt.
   */
  public ProfileSplitterBolt(String zookeeperUrl) {
    super(zookeeperUrl);
  }

  @Override
  public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
    super.prepare(stormConf, context, collector);
    this.collector = collector;
    this.parser = new JSONParser();
    initializeStellar();
  }

  protected void initializeStellar() {
    Context context = new Context.Builder()
                         .with(Context.Capabilities.ZOOKEEPER_CLIENT, () -> client)
                         .build();
    StellarFunctions.initialize(context);
    executor.setContext(context);
  }

  @Override
  public void execute(Tuple input) {
    try {
      doExecute(input);

    } catch (IllegalArgumentException | ParseException | UnsupportedEncodingException e) {
      LOG.error(format("Unexpected failure: message='%s', tuple='%s'", e.getMessage(), input), e);
      collector.reportError(e);

    } finally {
      collector.ack(input);
    }
  }

  private void doExecute(Tuple input) throws ParseException, UnsupportedEncodingException {

    // retrieve the input message
    byte[] data = input.getBinary(0);
    JSONObject message = (JSONObject) parser.parse(new String(data, "UTF8"));

    // ensure there is a valid profiler configuration
    ProfilerConfig config = getProfilerConfig();
    if(config == null) {
      throw new IllegalArgumentException("Fatal: Unable to find valid profiler definition");
    }

    // apply the message to each of the profile definitions
    for (ProfileConfig profile: config.getProfiles()) {
      applyProfile(profile, input, message);
    }
  }

  /**
   * Applies a message to a Profile definition.
   * @param profile The profile definition.
   * @param input The input tuple that delivered the message.
   * @param message The message that may be needed by the profile.
   */
  private void applyProfile(ProfileConfig profile, Tuple input, JSONObject message) throws ParseException, UnsupportedEncodingException {

    // is this message needed by this profile?
    String onlyIf = profile.getOnlyif();
    if (StringUtils.isBlank(onlyIf) || executor.execute(onlyIf, message, Boolean.class)) {

      // what is the name of the entity in this message?
      String entity = executor.execute(profile.getForeach(), message, String.class);

      // emit a message for the bolt responsible for building this profile
      collector.emit(input, new Values(entity, profile, message));
    }
  }

  /**
   * Each emitted tuple contains the following fields.
   * <p><ol>
   * <li> entity - The name of the entity.  The actual result of executing the Stellar expression.
   * <li> profile - The profile definition that the message needs applied to.
   * <li> message - The message containing JSON-formatted data that needs applied to a profile.
   * </ol></p>
   */
  @Override
  public void declareOutputFields(OutputFieldsDeclarer declarer) {
    declarer.declare(new Fields("entity", "profile", "message"));
  }

  public StellarExecutor getExecutor() {
    return executor;
  }

  public void setExecutor(StellarExecutor executor) {
    this.executor = executor;
  }
}
