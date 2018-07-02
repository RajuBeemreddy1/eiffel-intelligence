/*
   Copyright 2017 Ericsson AB.
   For a full list of individual contributors, please see the commit history.
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
       http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package com.ericsson.ei.utils;

import com.ericsson.ei.App;
import com.ericsson.ei.rmqhandler.RmqHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.bson.Document;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/**
 * @author evasiba
 *
 */
@Ignore
@RunWith(SpringRunner.class)
@SpringBootTest(classes = App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = App.class, loader = SpringBootContextLoader.class, initializers = TestContextInitializer.class)
@TestExecutionListeners(listeners = { DependencyInjectionTestExecutionListener.class, FunctionalTestBase.class })
public class FunctionalTestBase extends AbstractTestExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionalTestBase.class);

    @Autowired
    private MongoProperties mongoProperties;

    @Autowired
    private RmqHandler rmqHandler;

    @Value("${spring.data.mongodb.database}")
    private String database;

    @Value("${event_object_map.collection.name}")
    private String collection;

    @Value("${aggregated.collection.name}")
    private String aggregatedCollectionName;

    private MongoClient mongoClient;

    public int getMongoDbPort() {
        return mongoProperties.getPort();
    }

    public String getMongoDbHost() {
        return mongoProperties.getHost();
    }

    protected List<String> getEventNamesToSend() {
        return new ArrayList<>();
    }

    @Override
    public void beforeTestClass(TestContext testContext) throws Exception {
        // Before running test.
    }

    @Override
    public void afterTestClass(TestContext testContext) throws Exception {
        // After running tests.
    }

    /**
     * Send Eiffel Events to the waitlist queue. Takes a path to a JSON file
     * containing events and uses getEventNamesToSend to get specific events
     * from that file. getEventNamesToSend needs to be overridden.
     * 
     * @param eiffelEventsJsonPath
     *            JSON file containing Eiffel Events
     * @return list of eiffel event IDs
     * @throws InterruptedException
     * @throws IOException
     */
    protected List<String> sendEiffelEvents(String eiffelEventsJsonPath) throws InterruptedException, IOException {
        List<String> eventNames = getEventNamesToSend();
        List<String> eventsIdList = new ArrayList<>();

        JsonNode parsedJSON = getJSONFromFile(eiffelEventsJsonPath);

        for (String eventName : eventNames) {
            JsonNode eventJson = parsedJSON.get(eventName);
            eventsIdList.add(eventJson.get("meta").get("id").toString().replaceAll("\"", ""));
            rmqHandler.publishObjectToWaitlistQueue(eventJson.toString());
        }

        return eventsIdList;
    }

    /**
     * Converts a JSON string into a tree model.
     * 
     * @param filePath
     *            path to JSON file
     * @return JsonNode tree model
     * @throws IOException
     */
    protected JsonNode getJSONFromFile(String filePath) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        String expectedDocument = FileUtils.readFileToString(new File(filePath), "UTF-8");
        return objectMapper.readTree(expectedDocument);
    }

    /**
     * Verify that events are located in the database collection.
     * 
     * @param eventsIdList
     *            list of events IDs
     * @return list of missing events
     * @throws InterruptedException
     */
    protected List<String> verifyEventsInDB(List<String> eventsIdList) throws InterruptedException {
        List<String> checklist = new ArrayList<String>(eventsIdList);
        long stopTime = System.currentTimeMillis() + 30000;
        while (!checklist.isEmpty() && stopTime > System.currentTimeMillis()) {
            checklist = compareSentEventsWithEventsInDB(checklist);
            if (checklist.isEmpty()) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(1000);
        }
        return checklist;
    }

    /**
     * Checks collection of events against event list.
     * 
     * @param checklist
     *            list of event IDs
     * @return list of missing events
     */
    private List<String> compareSentEventsWithEventsInDB(List<String> checklist) {
        mongoClient = new MongoClient(getMongoDbHost(), getMongoDbPort());
        MongoDatabase db = mongoClient.getDatabase(database);
        MongoCollection<Document> table = db.getCollection(collection);
        List<Document> documents = table.find().into(new ArrayList<Document>());
        for (Document document : documents) {
            for (String expectedID : new ArrayList<String>(checklist)) {
                if (expectedID.equals(document.get("_id").toString())) {
                    checklist.remove(expectedID);
                }
            }
        }
        return checklist;
    }

    /**
     * Verify that aggregated object contains the expected information.
     * 
     * @param arguments
     *            list of arguments to check
     * @return list of missing arguments
     * @throws InterruptedException
     */
    protected List<String> verifyAggregatedObjectInDB(List<String> arguments) throws InterruptedException {
        List<String> checklist = new ArrayList<String>(arguments);
        MongoClient mongoClient = new MongoClient(getMongoDbHost(), getMongoDbPort());
        long stopTime = System.currentTimeMillis() + 30000;
        while (!checklist.isEmpty() && stopTime > System.currentTimeMillis()) {
            checklist = compareArgumentsWithAggregatedObjectInDB(checklist);
            if (checklist.isEmpty()) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(1000);
        }
        mongoClient.close();
        return checklist;
    }

    /**
     * Checks that aggregated object contains specified arguments.
     * 
     * @param checklist
     *            list of arguments
     * @return list of missing arguments
     */
    private List<String> compareArgumentsWithAggregatedObjectInDB(List<String> checklist) {
        MongoDatabase db = mongoClient.getDatabase(database);
        MongoCollection<Document> table = db.getCollection(aggregatedCollectionName);
        List<Document> documents = table.find().into(new ArrayList<Document>());
        for (Document document : documents) {
            for (String expectedValue : new ArrayList<String>(checklist)) {
                if (document.toString().contains(expectedValue)) {
                    checklist.remove(expectedValue);
                }
            }
        }
        return checklist;
    }
}