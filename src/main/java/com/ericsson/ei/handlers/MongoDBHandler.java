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
package com.ericsson.ei.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.stereotype.Component;

import com.ericsson.ei.exception.MongoDBConnectionException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.Multiset.Entry;
import com.mongodb.BasicDBObject;
import com.mongodb.Block;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.util.JSON;

import lombok.Getter;
import lombok.Setter;

@Component
public class MongoDBHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MongoDBHandler.class);

    @Getter
    @Autowired
    private MongoProperties mongoProperties;

    @Getter
    @Setter
    @JsonIgnore
    private MongoClient mongoClient;

    // TODO establish connection automatically when Spring instantiate this
    // based on connection data in properties file
    @PostConstruct
    public void init() {
        createConnection();
    }

    @PreDestroy
    public void close() {
        mongoClient.close();
    }

    // Establishing the connection to mongodb and creating a collection
    private void createConnection() {
        if (!StringUtils.isBlank(mongoProperties.getUsername())
                && !StringUtils.isBlank(new String(mongoProperties.getPassword()))) {
            ServerAddress address = new ServerAddress(mongoProperties.getHost(), mongoProperties.getPort());
            MongoCredential credential = MongoCredential.createCredential(mongoProperties.getUsername(),
                    mongoProperties.getDatabase(), mongoProperties.getPassword());
            mongoClient = new MongoClient(address, Collections.singletonList(credential));
        } else {
            mongoClient = new MongoClient(mongoProperties.getHost(), mongoProperties.getPort());
        }
    }

    /**
     * This method used for the insert the document into collection
     *
     * @param dataBaseName
     * @param collectionName
     * @param input          json String
     * @return
     */
    public void insertDocument(String dataBaseName, String collectionName, String input) throws MongoWriteException {

        try {
            long start = System.currentTimeMillis();
        	MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);           
            
            if (collection != null) {
                final Document dbObjectInput = Document.parse(input);
                collection.insertOne(dbObjectInput);
                long stop = System.currentTimeMillis();
                LOGGER.debug("#### Response time to insert the document in ms: {} ", stop-start);
                LOGGER.debug("Object: {}\n was inserted successfully in collection: {} and database {}.", input, collectionName, dataBaseName);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to insert Object: {} \n in collection: {} and database {}. \n {}", input,
                    collectionName, dataBaseName, e.getMessage());
        }
    }
    
    /**
     * This method is used to insert the Document object into collection
     * 
     * @param dataBaseName
     * @param collectionName
     * @param document - Document object to insert
     * @throws MongoWriteException
     */
    public void insertDocumentObject(String dataBaseName, String collectionName, Document document) throws MongoWriteException {
    	try {
        	MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);           

            if (collection != null) {
            	long start = System.currentTimeMillis();
                collection.insertOne(document);
                long stop = System.currentTimeMillis();
                LOGGER.debug("#### Response time to insert the document in ms: {} ", stop-start);
                LOGGER.debug("Object: {}\n was inserted successfully in collection: {} and database {}.", document, collectionName, dataBaseName);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to insert Object: {} \n in collection: {} and database {}. \n {}", document,
            		collectionName, dataBaseName, e.getMessage());
        }
    }

    /**
     * This method is used for the retrieve the all documents from the collection
     *
     * @param dataBaseName
     * @param collectionName
     * @return
     */
    public ArrayList<String> getAllDocuments(String dataBaseName, String collectionName) {
        ArrayList<String> result = new ArrayList<>();
        try {
            MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);
            if (collection != null) {
                collection.find(new BasicDBObject()).forEach((Block<Document>) document -> {
                    result.add(JSON.serialize(document));
                });
                if (result.size() != 0) {
                    // This will pass about 10 times/second and most of the times DB will be empty,
                    // this is normal, no need to log
                    LOGGER.debug("getAllDocuments() :: database: {} and collection: {} fetched No of : {}", dataBaseName, collectionName, result.size());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve documents.", e);
        }
        return result;
    }

    /**
     * This method is used for the retrieve the documents based on the condition
     *
     * @param dataBaseName
     * @param collectionName
     * @param condition      a condition to find a requested object in the database
     * @return
     */
    public ArrayList<String> find(String dataBaseName, String collectionName, String condition) {
        ArrayList<String> result = new ArrayList<>();

        LOGGER.debug("Find and retrieve data from database.\nDatabase: {}\nCollection: {}\nCondition/Query: {}", dataBaseName, collectionName, condition);

        try {
            MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);
            if (collection != null) {
                collection.find(BasicDBObject.parse(condition)).forEach((Block<Document>) document -> {
                    result.add(JSON.serialize(document));
                });
                if (result.size() != 0) {
                    LOGGER.debug("find() :: database: {} and collection: {} fetched No of : {}", dataBaseName, collectionName, result.size());
                } else {
                    LOGGER.debug("find() :: database: {} and collection: {} documents are not found", dataBaseName, collectionName);
                }
            } else {
                LOGGER.debug("Collection {} is empty in database {}", collectionName, dataBaseName);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve documents.", e);
        }

        return result;
    }

    /**
     * This method is used for update the document in collection and remove the lock
     * in one query. Lock is needed for multi process execution
     *
     * @param dataBaseName
     * @param collectionName
     * @param input          is a json string
     * @param updateInput    is updated document without lock
     * @return
     */
    public boolean updateDocument(String dataBaseName, String collectionName, String input, String updateInput) {
        try {
        	long start = System.currentTimeMillis();
        	MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);
            if (collection != null) {
                final Document dbObjectInput = Document.parse(input);
                final Document dbObjectUpdateInput = Document.parse(updateInput);
                UpdateResult updateMany = collection.replaceOne(dbObjectInput, dbObjectUpdateInput);
                long stop = System.currentTimeMillis();
                LOGGER.debug("#### Response time to update the document in ms: {} ", stop-start);
                LOGGER.debug("updateDocument() :: database: {} and collection: {} is document Updated : {}", dataBaseName, collectionName, updateMany.wasAcknowledged());
                               
                return updateMany.wasAcknowledged();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to update document.", e);
        }

        return false;
    }

    /**
     * This method is used for lock and return the document that matches the input
     * condition in one query. Lock is needed for multi process execution. This
     * method is executed in a loop.
     *
     * @param dataBaseName
     * @param collectionName
     * @param input          is a condition for update documents
     * @param updateInput    is updated document without lock
     * @return
     */
    public Document findAndModify(String dataBaseName, String collectionName, String input, String updateInput) {
        try {
            long start = System.currentTimeMillis();
            MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);
            if (collection != null) {
                final Document dbObjectInput = Document.parse(input);
                final Document dbObjectUpdateInput = Document.parse(updateInput);
                Document result = collection.findOneAndUpdate(dbObjectInput, dbObjectUpdateInput);
                if (result != null) {
                	long stop = System.currentTimeMillis();
                	LOGGER.debug("#### Response time to findAndModify the document in ms: {} ", stop-start);
                    LOGGER.debug("updateDocument() :: database: {} and collection: {} updated successfully", dataBaseName, collectionName);
                    return result;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to update document.", e);
        }
        return null;
    }

    /**
     * This method is used for the delete documents from collection using a
     * condition
     *
     * @param dataBaseName
     * @param collectionName
     * @param condition      string json
     * @return
     */
    public boolean dropDocument(String dataBaseName, String collectionName, String condition) {
        try {
            MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);
            if (collection != null) {
                final Document dbObjectCondition = Document.parse(condition);
                DeleteResult deleteMany = collection.deleteMany(dbObjectCondition);
                if (deleteMany.getDeletedCount() > 0) {
                    LOGGER.debug("database: {} and collection: {} deleted No.of records {}", dataBaseName, collectionName, deleteMany.getDeletedCount());
                    return true;
                } else {
                    LOGGER.debug("database {} and collection: {} No documents found to delete.", dataBaseName, collectionName);
                    return false;
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
        return false;
    }

    /**
     * This method is used for the create time to live index
     *
     * @param dataBaseName
     * @param collectionName
     * @param fieldName      for index creation field
     * @param ttlValue       seconds
     * @throws MongoDBConnectionException
     */
	public void createTTLIndex(String dataBaseName, String collectionName, String fieldName, int ttlValue)
			throws MongoDBConnectionException {
		try {
			MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);
			IndexOptions indexOptions = new IndexOptions().expireAfter((long) ttlValue, TimeUnit.SECONDS);

			// Dropping the previous index on the collection to avoid exceptions
			// when configuration changes for the fieldName.
			dropTTLIndex(collection, fieldName);
			collection.createIndex(Indexes.ascending(fieldName), indexOptions);
		} catch (Exception e) {
			throw new MongoDBConnectionException("MongoDB Connection down", e);
		}
	}	
  
	private void dropTTLIndex(final MongoCollection<Document> collection, String fieldName) throws Exception {
	    boolean indexExists = false;
	    for (Document index : collection.listIndexes()) {
	        for (Map.Entry<String, Object> entry : index.entrySet()) {
	            Object value = entry.getValue();
	            if(value.equals(fieldName + "_1")) {
	                indexExists = true;
	                break;
	            }
	        }
	    }

	    if(indexExists) {
	        LOGGER.debug("Dropping the index for " + collection.getNamespace());
	        collection.dropIndex(fieldName + "_1");     
	    }
	}

    private MongoCollection<Document> getMongoCollection(final String dataBaseName, final String collectionName) throws MongoClientException{
    	
    	if (mongoClient == null) {
            throw new MongoClientException("Failed to connect MongoDB");
        }

        MongoCollection<Document> collection = null;
        String errorMessage = null;
        MongoDatabase db;
        try {
            db = mongoClient.getDatabase(dataBaseName);
            collection = db.getCollection(collectionName);
        }        
        catch (final IllegalArgumentException e) {
        	errorMessage = String.format("IllegalArgumentException, Collection name was {} Illegal: %s",
                    e.getMessage());
            throw new MongoClientException(errorMessage, e);
        }
        catch (final MongoCommandException e) {
        	errorMessage = String.format("MongoCommandException, Something went wrong with MongoDb connection, Reason: %s",
                    e.getMessage()); 
            throw new MongoClientException(errorMessage, e);
        }
        catch (final MongoInterruptedException e) {
        	errorMessage = String.format("MongoInterruptedException, MongoDB shutdown or interrupted. Reason: %s",
                    e.getMessage());             
            throw new MongoClientException(errorMessage, e);
        }
        catch (final MongoSocketReadException e) {
        	errorMessage = String.format("MongoSocketReadException, MongoDB shutdown or interrupted, Reason: %s",
                    e.getMessage());            
            throw new MongoClientException(errorMessage, e);
        }
        catch (final IllegalStateException e) {
        	errorMessage = String.format("IllegalStateException, MongoDB state not good. Reason: %s",
                    e.getMessage());             
            throw new MongoClientException(errorMessage, e);
        }
        if (collection == null) {
            LOGGER.debug("The requested database({}) / collection({}) not available in mongodb, Creating ........", dataBaseName, collectionName);
            try {
                db.createCollection(collectionName);
            } catch (final MongoCommandException e) {
                final String message = "collection '" + dataBaseName + "." + collectionName + "' already exists";
                if (e.getMessage().contains(message)) {
                    LOGGER.warn("A {}.", message, e);
                } else {
                    throw e;
                }
            }
            collection = db.getCollection(collectionName);
            LOGGER.debug("done....");            
        }        
        return collection;
    }	
	
    /**
     * This method is used to drop a collection.
     *
     * @param dataBaseName   to know which database to drop a collection from
     * @param collectionName to know which collection to drop
     */
    public void dropCollection(String dataBaseName, String collectionName) {
        MongoDatabase db = mongoClient.getDatabase(dataBaseName);
        MongoCollection<Document> mongoCollection = db.getCollection(collectionName);
        mongoCollection.drop();
    }

    /**
     * This method is used to drop a database. For example after testing.
     *
     * @param dataBaseName to know which database to remove
     */
    public void dropDatabase(String databaseName) {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        db.drop();
    }
    
    /**
     * Check if the document exists
     * @param databaseName
     * @param collectionName
     * @param condition      a condition to find a requested object in the database
     * @return
     */
    public boolean checkDocumentExists(String databaseName, String collectionName, String condition) {

        try {
        	long start = System.currentTimeMillis();
            MongoDatabase db = mongoClient.getDatabase(databaseName);
            MongoCollection<Document> mongoCollection = db.getCollection(collectionName);
            Document doc = null;
            if (mongoCollection != null) {
                doc = mongoCollection.find(BasicDBObject.parse(condition)).first();
            }
            if (doc == null || doc.isEmpty()) {
                return false;
            }
            long stop = System.currentTimeMillis();
            LOGGER.debug("#### Response time to checkDocumentExists in ms: {} ", stop-start);
        } catch (Exception e) {
            LOGGER.error("something wrong with MongoDB " + e);
            return false;
        }
        return true;
    }
    
    
    /**
     * Update the existing documents with unique objects list
     * Used only in EventToObjectMapHandler.java
     * @param dataBaseName
     * @param collectionName
     * @param condition      a condition to find a requested object in the database
     * @param eventId eventId to update in the mapper collection
     * @return 
     */
    public boolean updateDocumentAddToSet(String dataBaseName, String collectionName, String condition, String eventId) {
    	try {
            long start = System.currentTimeMillis();
            MongoCollection<Document> collection = getMongoCollection(dataBaseName, collectionName);
            if (collection != null) {
                final Document dbObjectInput = Document.parse(condition);  
                
                UpdateResult updateMany = collection.updateOne(dbObjectInput, Updates.addToSet("objects", eventId));
                updateMany = collection.updateOne(dbObjectInput, Updates.set("Time", DateUtils.getDate()));
                long stop = System.currentTimeMillis();
                LOGGER.debug("#### Response time to updateDocumentAddToSet in ms: {} ", stop-start);
                LOGGER.debug("updateDocument() :: database: {} and collection: {} is document Updated : {}", dataBaseName, collectionName, updateMany.wasAcknowledged());
                return updateMany.wasAcknowledged();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to update document.", e);
        }

        return false;
    }


	/**
	 * 
	 * This method checks the mongoDB connection status.
	 * 
	 * @param dataBaseName
	 * @return true if the connection is up otherwise return false
	 */
	public boolean checkMongoDbStatus(String dataBaseName) {
		MongoDatabase db;
		List<String> collectionList;
		try {
			if (mongoClient == null) {
				createConnection();
			}
			db = mongoClient.getDatabase(dataBaseName);
			collectionList = db.listCollectionNames().into(new ArrayList<String>());
		} catch (Exception e) {
			LOGGER.error("MongoCommandException, Something went wrong with MongoDb connection. Error: " + e);
			return false;
		}
		if (collectionList == null || collectionList.isEmpty()) {
			return false;
		}
		return true;
	}
}
