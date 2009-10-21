/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.service;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.management.*;

import org.apache.cassandra.concurrent.*;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.dht.*;
import org.apache.cassandra.gms.*;
import org.apache.cassandra.locator.*;
import org.apache.cassandra.net.*;
import org.apache.cassandra.net.io.StreamContextManager;
import org.apache.cassandra.tools.MembershipCleanerVerbHandler;
import org.apache.cassandra.utils.FileUtils;
import org.apache.cassandra.utils.LogUtil;
import org.apache.cassandra.io.SSTableReader;

import org.apache.log4j.Logger;
import org.apache.log4j.Level;

/*
 * This abstraction contains the token/identifier of this node
 * on the identifier space. This token gets gossiped around.
 * This class will also maintain histograms of the load information
 * of other nodes in the cluster.
 */
public final class StorageService implements IEndPointStateChangeSubscriber, StorageServiceMBean
{
    private static Logger logger_ = Logger.getLogger(StorageService.class);     
    private final static String nodeId_ = "NODE-IDENTIFIER";
    public final static String BOOTSTRAP_MODE = "BOOTSTRAP-MODE";
    
    /* All stage identifiers */
    public final static String mutationStage_ = "ROW-MUTATION-STAGE";
    public final static String readStage_ = "ROW-READ-STAGE";
    
    /* All verb handler identifiers */
    public final static String mutationVerbHandler_ = "ROW-MUTATION-VERB-HANDLER";
    public final static String tokenVerbHandler_ = "TOKEN-VERB-HANDLER";
    public final static String binaryVerbHandler_ = "BINARY-VERB-HANDLER";
    public final static String readRepairVerbHandler_ = "READ-REPAIR-VERB-HANDLER";
    public final static String readVerbHandler_ = "ROW-READ-VERB-HANDLER";
    public final static String bootStrapInitiateVerbHandler_ = "BOOTSTRAP-INITIATE-VERB-HANDLER";
    public final static String bootStrapInitiateDoneVerbHandler_ = "BOOTSTRAP-INITIATE-DONE-VERB-HANDLER";
    public final static String bootStrapTerminateVerbHandler_ = "BOOTSTRAP-TERMINATE-VERB-HANDLER";
    public final static String dataFileVerbHandler_ = "DATA-FILE-VERB-HANDLER";
    public final static String mbrshipCleanerVerbHandler_ = "MBRSHIP-CLEANER-VERB-HANDLER";
    public final static String bootstrapMetadataVerbHandler_ = "BS-METADATA-VERB-HANDLER";
    public final static String rangeVerbHandler_ = "RANGE-VERB-HANDLER";
    public final static String bootstrapTokenVerbHandler_ = "SPLITS-VERB-HANDLER";

    private static EndPoint tcpAddr_;
    private static EndPoint udpAddr_;
    private static IPartitioner partitioner_ = DatabaseDescriptor.getPartitioner();


    private static volatile StorageService instance_;

    public static EndPoint getLocalStorageEndPoint()
    {
        return tcpAddr_;
    }

    public static EndPoint getLocalControlEndPoint()
    {
        return udpAddr_;
    }

    public static IPartitioner<?> getPartitioner() {
        return partitioner_;
    }

    public Set<Range> getLocalRanges()
    {
        return getRangesForEndPoint(getLocalStorageEndPoint());
    }

    public Range getLocalPrimaryRange()
    {
        return getPrimaryRangeForEndPoint(getLocalStorageEndPoint());
    }

    /*
     * Factory method that gets an instance of the StorageService
     * class.
    */
    public static StorageService instance()
    {
        if (instance_ == null)
        {
            synchronized (StorageService.class)
            {
                if (instance_ == null)
                {
                    try
                    {
                        instance_ = new StorageService();
                    }
                    catch (Throwable th)
                    {
                        logger_.error(LogUtil.throwableToString(th));
                        System.exit(1);
                    }
                }
            }
        }
        return instance_;
    }

    /*
     * This is the endpoint snitch which depends on the network architecture. We
     * need to keep this information for each endpoint so that we make decisions
     * while doing things like replication etc.
     *
     */
    private IEndPointSnitch endPointSnitch_;

    /* This abstraction maintains the token/endpoint metadata information */
    private TokenMetadata tokenMetadata_ = new TokenMetadata();
    private SystemTable.StorageMetadata storageMetadata_;

    /* This thread pool does consistency checks when the client doesn't care about consistency */
    private ExecutorService consistencyManager_ = new DebuggableThreadPoolExecutor(DatabaseDescriptor.getConsistencyThreads(),
                                                                                   DatabaseDescriptor.getConsistencyThreads(),
                                                                                   Integer.MAX_VALUE,
                                                                                   TimeUnit.SECONDS,
                                                                                   new LinkedBlockingQueue<Runnable>(),
                                                                                   new NamedThreadFactory("CONSISTENCY-MANAGER"));

    /* We use this interface to determine where replicas need to be placed */
    private AbstractReplicationStrategy replicationStrategy_;
    /* Are we starting this node in bootstrap mode? */
    private boolean isBootstrapMode;
    private Set<EndPoint> bootstrapSet;
  
    public synchronized void addBootstrapSource(EndPoint s)
    {
        if (logger_.isDebugEnabled())
            logger_.debug("Added " + s.getHost() + " as a bootstrap source");
        bootstrapSet.add(s);
    }
    
    public synchronized boolean removeBootstrapSource(EndPoint s)
    {
        bootstrapSet.remove(s);

        if (logger_.isDebugEnabled())
            logger_.debug("Removed " + s.getHost() + " as a bootstrap source");
        if (bootstrapSet.isEmpty())
        {
            SystemTable.setBootstrapped();
            isBootstrapMode = false;
            updateTokenMetadata(storageMetadata_.getToken(), StorageService.tcpAddr_, false);

            logger_.info("Bootstrap completed! Now serving reads.");
            /* Tell others you're not bootstrapping anymore */
            Gossiper.instance().deleteApplicationState(BOOTSTRAP_MODE);
        }
        return isBootstrapMode;
    }

    private void updateTokenMetadata(Token token, EndPoint endpoint, boolean isBootstraping)
    {
        tokenMetadata_.update(token, endpoint, isBootstraping);
        if (!isBootstraping)
        {
            try
            {
                SystemTable.updateToken(endpoint, token);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public StorageService()
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(this, new ObjectName("org.apache.cassandra.service:type=StorageService"));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        bootstrapSet = new HashSet<EndPoint>();
        endPointSnitch_ = DatabaseDescriptor.getEndPointSnitch();

        /* register the verb handlers */
        MessagingService.instance().registerVerbHandlers(tokenVerbHandler_, new TokenUpdateVerbHandler());
        MessagingService.instance().registerVerbHandlers(binaryVerbHandler_, new BinaryVerbHandler());
        MessagingService.instance().registerVerbHandlers(mutationVerbHandler_, new RowMutationVerbHandler());
        MessagingService.instance().registerVerbHandlers(readRepairVerbHandler_, new ReadRepairVerbHandler());
        MessagingService.instance().registerVerbHandlers(readVerbHandler_, new ReadVerbHandler());
        MessagingService.instance().registerVerbHandlers(dataFileVerbHandler_, new DataFileVerbHandler() );
        MessagingService.instance().registerVerbHandlers(mbrshipCleanerVerbHandler_, new MembershipCleanerVerbHandler() );
        MessagingService.instance().registerVerbHandlers(rangeVerbHandler_, new RangeVerbHandler());
        // see BootStrapper for a summary of how the bootstrap verbs interact
        MessagingService.instance().registerVerbHandlers(bootstrapTokenVerbHandler_, new BootStrapper.BootstrapTokenVerbHandler());
        MessagingService.instance().registerVerbHandlers(bootstrapMetadataVerbHandler_, new BootstrapMetadataVerbHandler() );
        MessagingService.instance().registerVerbHandlers(bootStrapInitiateVerbHandler_, new BootStrapper.BootStrapInitiateVerbHandler());
        MessagingService.instance().registerVerbHandlers(bootStrapInitiateDoneVerbHandler_, new BootStrapper.BootstrapInitiateDoneVerbHandler());
        MessagingService.instance().registerVerbHandlers(bootStrapTerminateVerbHandler_, new BootStrapper.BootstrapTerminateVerbHandler());

        StageManager.registerStage(StorageService.mutationStage_,
                                   new MultiThreadedStage(StorageService.mutationStage_, DatabaseDescriptor.getConcurrentWriters()));
        StageManager.registerStage(StorageService.readStage_,
                                   new MultiThreadedStage(StorageService.readStage_, DatabaseDescriptor.getConcurrentReaders()));

        Class cls = DatabaseDescriptor.getReplicaPlacementStrategyClass();
        Class [] parameterTypes = new Class[] { TokenMetadata.class, IPartitioner.class, int.class, int.class};
        try
        {
            replicationStrategy_ = (AbstractReplicationStrategy) cls.getConstructor(parameterTypes).newInstance(tokenMetadata_, partitioner_, DatabaseDescriptor.getReplicationFactor(), DatabaseDescriptor.getStoragePort());
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public void start() throws IOException
    {
        storageMetadata_ = SystemTable.initMetadata();
        tcpAddr_ = new EndPoint(DatabaseDescriptor.getStoragePort());
        udpAddr_ = new EndPoint(DatabaseDescriptor.getControlPort());
        isBootstrapMode = DatabaseDescriptor.isAutoBootstrap()
                          && !(DatabaseDescriptor.getSeeds().contains(udpAddr_.getHost()) || SystemTable.isBootstrapped());

        /* Listen for application messages */
        MessagingService.instance().listen(tcpAddr_);
        /* Listen for control messages */
        MessagingService.instance().listenUDP(udpAddr_);

        SelectorManager.getSelectorManager().start();
        SelectorManager.getUdpSelectorManager().start();

        StorageLoadBalancer.instance().startBroadcasting();

        // have to start the gossip service before we can see any info on other nodes.  this is necessary
        // for bootstrap to get the load info it needs.
        // (we won't be part of the storage ring though until we add a nodeId to our state, below.)
        Gossiper.instance().register(this);
        Gossiper.instance().start(udpAddr_, storageMetadata_.getGeneration());

        if (isBootstrapMode)
        {
            BootStrapper.startBootstrap(); // handles token update
        }
        else
        {
            SystemTable.setBootstrapped();
            tokenMetadata_.update(storageMetadata_.getToken(), StorageService.tcpAddr_, isBootstrapMode);
        }

        // Gossip my token.
        // note that before we do this we've (a) finalized what the token is actually going to be, and
        // (b) added a bootstrap state (done by startBootstrap)
        ApplicationState state = new ApplicationState(StorageService.getPartitioner().getTokenFactory().toString(storageMetadata_.getToken()));
        Gossiper.instance().addApplicationState(StorageService.nodeId_, state);
    }

    public boolean isBootstrapMode()
    {
        return isBootstrapMode;
    }

    public TokenMetadata getTokenMetadata()
    {
        return tokenMetadata_.cloneMe();
    }

    /* TODO: used for testing */
    public void updateTokenMetadataUnsafe(Token token, EndPoint endpoint)
    {
        tokenMetadata_.update(token, endpoint);
    }

    public IEndPointSnitch getEndPointSnitch()
    {
    	return endPointSnitch_;
    }
    
    /*
     * Given an EndPoint this method will report if the
     * endpoint is in the same data center as the local
     * storage endpoint.
    */
    public boolean isInSameDataCenter(EndPoint endpoint) throws IOException
    {
        return endPointSnitch_.isInSameDataCenter(StorageService.tcpAddr_, endpoint);
    }
    
    /*
     * This method performs the requisite operations to make
     * sure that the N replicas are in sync. We do this in the
     * background when we do not care much about consistency.
     */
    public void doConsistencyCheck(Row row, List<EndPoint> endpoints, ReadCommand command)
    {
        Runnable consistencySentinel = new ConsistencyManager(row.cloneMe(), endpoints, command);
        consistencyManager_.submit(consistencySentinel);
    }

    public Map<Range, List<String>> getRangeToEndPointMap()
    {
        /* Get the token to endpoint map. */
        Map<Token, EndPoint> tokenToEndPointMap = tokenMetadata_.cloneTokenEndPointMap();
        /* All the ranges for the tokens */
        Range[] ranges = getAllRanges(tokenToEndPointMap.keySet());
        Map<Range, List<String>> map = new HashMap<Range, List<String>>();
        for (Map.Entry<Range,List<EndPoint>> entry : constructRangeToEndPointMap(ranges).entrySet())
        {
            map.put(entry.getKey(), stringify(entry.getValue()));
        }
        return map;
    }

    /**
     * Construct the range to endpoint mapping based on the true view 
     * of the world. 
     * @param ranges
     * @return mapping of ranges to the replicas responsible for them.
    */
    public Map<Range, List<EndPoint>> constructRangeToEndPointMap(Range[] ranges)
    {
        Map<Range, List<EndPoint>> rangeToEndPointMap = new HashMap<Range, List<EndPoint>>();
        for (Range range : ranges)
        {
            EndPoint[] endpoints = replicationStrategy_.getReadStorageEndPoints(range.right());
            // create a new ArrayList since a bunch of methods like to mutate the endpointmap List
            rangeToEndPointMap.put(range, new ArrayList<EndPoint>(Arrays.asList(endpoints)));
        }
        return rangeToEndPointMap;
    }
    
    /**
     * Construct the range to endpoint mapping based on the view as dictated
     * by the mapping of token to endpoints passed in. 
     * @param ranges
     * @param tokenToEndPointMap mapping of token to endpoints.
     * @return mapping of ranges to the replicas responsible for them.
    */
    public Map<Range, List<EndPoint>> constructRangeToEndPointMap(Range[] ranges, Map<Token, EndPoint> tokenToEndPointMap)
    {
        if (logger_.isDebugEnabled())
          logger_.debug("Constructing range to endpoint map ...");
        Map<Range, List<EndPoint>> rangeToEndPointMap = new HashMap<Range, List<EndPoint>>();
        for ( Range range : ranges )
        {
            EndPoint[] endpoints = replicationStrategy_.getReadStorageEndPoints(range.right(), tokenToEndPointMap);
            rangeToEndPointMap.put(range, new ArrayList<EndPoint>( Arrays.asList(endpoints) ) );
        }
        if (logger_.isDebugEnabled())
          logger_.debug("Done constructing range to endpoint map ...");
        return rangeToEndPointMap;
    }

    /**
     *  Called when there is a change in application state. In particular
     *  we are interested in new tokens as a result of a new node or an
     *  existing node moving to a new location on the ring.
    */
    public void onChange(EndPoint endpoint, EndPointState epState)
    {
        EndPoint ep = new EndPoint(endpoint.getHost(), DatabaseDescriptor.getStoragePort());
        /* node identifier for this endpoint on the identifier space */
        ApplicationState nodeIdState = epState.getApplicationState(StorageService.nodeId_);
        /* Check if this has a bootstrapping state message */
        boolean bootstrapState = epState.getApplicationState(StorageService.BOOTSTRAP_MODE) != null;
        if (bootstrapState)
        {
            if (logger_.isDebugEnabled())
                logger_.debug(ep.getHost() + " is in bootstrap state.");
        }
        if (nodeIdState != null)
        {
            Token newToken = getPartitioner().getTokenFactory().fromString(nodeIdState.getState());
            if (logger_.isDebugEnabled())
              logger_.debug("CHANGE IN STATE FOR " + endpoint + " - has token " + nodeIdState.getState());
            Token oldToken = tokenMetadata_.getToken(ep);

            if ( oldToken != null )
            {
                /*
                 * If oldToken equals the newToken then the node had crashed
                 * and is coming back up again. If oldToken is not equal to
                 * the newToken this means that the node is being relocated
                 * to another position in the ring.
                */
                if ( !oldToken.equals(newToken) )
                {
                    if (logger_.isDebugEnabled())
                      logger_.debug("Relocation for endpoint " + ep);
                    updateTokenMetadata(newToken, ep, bootstrapState);
                }
                else
                {
                    /*
                     * This means the node crashed and is coming back up.
                     * Deliver the hints that we have for this endpoint.
                    */
                    if (logger_.isDebugEnabled())
                      logger_.debug("Sending hinted data to " + ep);
                    deliverHints(endpoint);
                }
            }
            else
            {
                /*
                 * This is a new node and we just update the token map.
                */
                updateTokenMetadata(newToken, ep, bootstrapState);
            }
        }
        else
        {
            /*
             * If we are here and if this node is UP and already has an entry
             * in the token map. It means that the node was behind a network partition.
            */
            if ( epState.isAlive() && tokenMetadata_.isKnownEndPoint(endpoint) )
            {
                if (logger_.isDebugEnabled())
                  logger_.debug("EndPoint " + ep + " just recovered from a partition. Sending hinted data.");
                deliverHints(ep);
            }
        }
    }

    /** raw load value */
    public double getLoad()
    {
        return FileUtils.getUsedDiskSpace();
    }

    public String getLoadString()
    {
        return FileUtils.stringifyFileSize(FileUtils.getUsedDiskSpace());
    }

    public Map<String, String> getLoadMap()
    {
        Map<String, String> map = new HashMap<String, String>();
        for (Map.Entry<EndPoint,Double> entry : StorageLoadBalancer.instance().getLoadInfo().entrySet())
        {
            map.put(entry.getKey().getHost(), FileUtils.stringifyFileSize(entry.getValue()));
        }
        // gossiper doesn't bother sending to itself, so if there are no other nodes around
        // we need to cheat to get load information for the local node
        if (!map.containsKey(getLocalControlEndPoint().getHost()))
        {
            map.put(getLocalControlEndPoint().getHost(), getLoadString());
        }
        return map;
    }

    /*
     * This method updates the token on disk and modifies the cached
     * StorageMetadata instance. This is only for the local endpoint.
    */
    public void updateToken(Token token) throws IOException
    {
        if (logger_.isDebugEnabled())
          logger_.debug("Setting token to " + token);
        /* update the token on disk */
        SystemTable.updateToken(token);
        /* Update the token maps */
        tokenMetadata_.update(token, StorageService.tcpAddr_);
        /* Gossip this new token for the local storage instance */
        ApplicationState state = new ApplicationState(StorageService.getPartitioner().getTokenFactory().toString(token));
        Gossiper.instance().addApplicationState(StorageService.nodeId_, state);
    }
    
    /*
     * This method removes the state associated with this endpoint
     * from the TokenMetadata instance.
     * 
     *  @param endpoint remove the token state associated with this 
     *         endpoint.
     */
    public void removeTokenState(EndPoint endpoint) 
    {
        tokenMetadata_.remove(endpoint);
        /* Remove the state from the Gossiper */
        Gossiper.instance().removeFromMembership(endpoint);
    }

    /**
     * Deliver hints to the specified node when it has crashed
     * and come back up/ marked as alive after a network partition
    */
    public final void deliverHints(EndPoint endpoint)
    {
        HintedHandOffManager.instance().deliverHints(endpoint);
    }

    public Token getLocalToken()
    {
        return tokenMetadata_.getToken(tcpAddr_);
    }

    /* This methods belong to the MBean interface */

    public String getToken()
    {
        return getLocalToken().toString();
    }

    public Set<String> getLiveNodes()
    {
        return stringify(Gossiper.instance().getLiveMembers());
    }

    public Set<String> getUnreachableNodes()
    {
        return stringify(Gossiper.instance().getUnreachableMembers());
    }

    private Set<String> stringify(Set<EndPoint> endPoints)
    {
        Set<String> stringEndPoints = new HashSet<String>();
        for (EndPoint ep : endPoints)
        {
            stringEndPoints.add(ep.getHost());
        }
        return stringEndPoints;
    }

    private List<String> stringify(List<EndPoint> endPoints)
    {
        List<String> stringEndPoints = new ArrayList<String>();
        for (EndPoint ep : endPoints)
        {
            stringEndPoints.add(ep.getHost());
        }
        return stringEndPoints;
    }

    public int getCurrentGenerationNumber()
    {
        return Gossiper.instance().getCurrentGenerationNumber(udpAddr_);
    }

    public void forceTableCleanup() throws IOException
    {
        List<String> tables = DatabaseDescriptor.getTables();
        for ( String tName : tables )
        {
            Table table = Table.open(tName);
            table.forceCleanup();
        }
    }
    
    public void forceTableCompaction() throws IOException
    {
        List<String> tables = DatabaseDescriptor.getTables();
        for ( String tName : tables )
        {
            Table table = Table.open(tName);
            table.forceCompaction();
        }        
    }
    
    public void forceHandoff(List<String> dataDirectories, String host) throws IOException
    {       
        List<File> filesList = new ArrayList<File>();
        List<StreamContextManager.StreamContext> streamContexts = new ArrayList<StreamContextManager.StreamContext>();
        
        for (String dataDir : dataDirectories)
        {
            File directory = new File(dataDir);
            Collections.addAll(filesList, directory.listFiles());            
        

            for (File tableDir : directory.listFiles())
            {
                String tableName = tableDir.getName();

                for (File file : tableDir.listFiles())
                {
                    streamContexts.add(new StreamContextManager.StreamContext(file.getAbsolutePath(), file.length(), tableName));
                    if (logger_.isDebugEnabled())
                      logger_.debug("Stream context metadata " + streamContexts);
                }
            }
        }
        
        if ( streamContexts.size() > 0 )
        {
            EndPoint target = new EndPoint(host, DatabaseDescriptor.getStoragePort());
            /* Set up the stream manager with the files that need to streamed */
            final StreamContextManager.StreamContext[] contexts = streamContexts.toArray(new StreamContextManager.StreamContext[streamContexts.size()]);
            StreamManager.instance(target).addFilesToStream(contexts);
            /* Send the bootstrap initiate message */
            final StreamContextManager.StreamContext[] bootContexts = streamContexts.toArray(new StreamContextManager.StreamContext[streamContexts.size()]);
            BootstrapInitiateMessage biMessage = new BootstrapInitiateMessage(bootContexts);
            Message message = BootstrapInitiateMessage.makeBootstrapInitiateMessage(biMessage);
            if (logger_.isDebugEnabled())
              logger_.debug("Sending a bootstrap initiate message to " + target + " ...");
            MessagingService.instance().sendOneWay(message, target);
            if (logger_.isDebugEnabled())
              logger_.debug("Waiting for transfer to " + target + " to complete");
            StreamManager.instance(target).waitForStreamCompletion();
            if (logger_.isDebugEnabled())
              logger_.debug("Done with transfer to " + target);  
        }
    }

    /**
     * Takes the snapshot for a given table.
     * 
     * @param tableName the name of the table.
     * @param tag   the tag given to the snapshot (null is permissible)
     */
    public void takeSnapshot(String tableName, String tag) throws IOException
    {
    	if (DatabaseDescriptor.getTable(tableName) == null)
        {
            throw new IOException("Table " + tableName + "does not exist");
    	}
        Table tableInstance = Table.open(tableName);
        tableInstance.snapshot(tag);
    }
    
    /**
     * Takes a snapshot for every table.
     * 
     * @param tag the tag given to the snapshot (null is permissible)
     */
    public void takeAllSnapshot(String tag) throws IOException
    {
    	for (String tableName: DatabaseDescriptor.getTables())
        {
            Table tableInstance = Table.open(tableName);
            tableInstance.snapshot(tag);
    	}
    }

    /**
     * Remove all the existing snapshots.
     */
    public void clearSnapshot() throws IOException
    {
    	for (String tableName: DatabaseDescriptor.getTables())
        {
            Table tableInstance = Table.open(tableName);
            tableInstance.clearSnapshot();
    	}
        if (logger_.isDebugEnabled())
            logger_.debug("Cleared out all snapshot directories");
    }

    public void forceTableFlushBinary(String tableName) throws IOException
    {
        if (DatabaseDescriptor.getTable(tableName) == null)
        {
            throw new IOException("Table " + tableName + "does not exist");
        }

        Table table = Table.open(tableName);
        Set<String> columnFamilies = table.getColumnFamilies();
        for (String columnFamily : columnFamilies)
        {
            ColumnFamilyStore cfStore = table.getColumnFamilyStore(columnFamily);
            logger_.debug("Forcing flush on keyspace " + tableName + " on CF " + columnFamily);
            cfStore.forceFlushBinary();
        }
    }


    /* End of MBean interface methods */
    
    /**
     * This method returns the predecessor of the endpoint ep on the identifier
     * space.
     */
    EndPoint getPredecessor(EndPoint ep)
    {
        Token token = tokenMetadata_.getToken(ep);
        return tokenMetadata_.getEndPoint(replicationStrategy_.getPredecessor(token, tokenMetadata_.cloneTokenEndPointMap()));
    }

    /*
     * This method returns the successor of the endpoint ep on the identifier
     * space.
     */
    public EndPoint getSuccessor(EndPoint ep)
    {
        Token token = tokenMetadata_.getToken(ep);
        return tokenMetadata_.getEndPoint(replicationStrategy_.getSuccessor(token, tokenMetadata_.cloneTokenEndPointMap()));
    }

    /**
     * Get the primary range for the specified endpoint.
     * @param ep endpoint we are interested in.
     * @return range for the specified endpoint.
     */
    public Range getPrimaryRangeForEndPoint(EndPoint ep)
    {
        Token right = tokenMetadata_.getToken(ep);
        return replicationStrategy_.getPrimaryRangeFor(right, tokenMetadata_.cloneTokenEndPointMap());
    }
    
    /**
     * Get all ranges an endpoint is responsible for.
     * @param ep endpoint we are interested in.
     * @return ranges for the specified endpoint.
     */
    Set<Range> getRangesForEndPoint(EndPoint ep)
    {
        return replicationStrategy_.getRangeMap().get(ep);
    }
        
    /**
     * Get all ranges that span the ring given a set
     * of tokens. All ranges are in sorted order of 
     * ranges.
     * @return ranges in sorted order
    */
    public Range[] getAllRanges(Set<Token> tokens)
    {
        List<Range> ranges = new ArrayList<Range>();
        List<Token> allTokens = new ArrayList<Token>(tokens);
        Collections.sort(allTokens);
        int size = allTokens.size();
        for ( int i = 1; i < size; ++i )
        {
            Range range = new Range( allTokens.get(i - 1), allTokens.get(i) );
            ranges.add(range);
        }
        Range range = new Range( allTokens.get(size - 1), allTokens.get(0) );
        ranges.add(range);
        return ranges.toArray( new Range[0] );
    }

    /**
     * This method returns the endpoint that is responsible for storing the
     * specified key.
     *
     * @param key - key for which we need to find the endpoint
     * @return value - the endpoint responsible for this key
     */
    public EndPoint getPrimary(String key)
    {
        EndPoint endpoint = StorageService.tcpAddr_;
        Token token = partitioner_.getToken(key);
        Map<Token, EndPoint> tokenToEndPointMap = tokenMetadata_.cloneTokenEndPointMap();
        List tokens = new ArrayList<Token>(tokenToEndPointMap.keySet());
        if (tokens.size() > 0)
        {
            Collections.sort(tokens);
            int index = Collections.binarySearch(tokens, token);
            if (index >= 0)
            {
                /*
                 * retrieve the endpoint based on the token at this index in the
                 * tokens list
                 */
                endpoint = tokenToEndPointMap.get(tokens.get(index));
            }
            else
            {
                index = (index + 1) * (-1);
                if (index < tokens.size())
                    endpoint = tokenToEndPointMap.get(tokens.get(index));
                else
                    endpoint = tokenToEndPointMap.get(tokens.get(0));
            }
        }
        return endpoint;
    }

    /**
     * This method determines whether the local endpoint is the
     * primary for the given key.
     * @param key
     * @return true if the local endpoint is the primary replica.
    */
    public boolean isPrimary(String key)
    {
        EndPoint endpoint = getPrimary(key);
        return StorageService.tcpAddr_.equals(endpoint);
    }

    /**
     * This method returns the N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param key - key for which we need to find the endpoint return value -
     * the endpoint responsible for this key
     */
    public EndPoint[] getReadStorageEndPoints(String key)
    {
        return replicationStrategy_.getReadStorageEndPoints(partitioner_.getToken(key));
    }    
    
    /**
     * This method attempts to return N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param key - key for which we need to find the endpoint return value -
     * the endpoint responsible for this key
     */
    public List<EndPoint> getLiveReadStorageEndPoints(String key)
    {
    	List<EndPoint> liveEps = new ArrayList<EndPoint>();
    	EndPoint[] endpoints = getReadStorageEndPoints(key);
    	
    	for ( EndPoint endpoint : endpoints )
    	{
    		if ( FailureDetector.instance().isAlive(endpoint) )
    			liveEps.add(endpoint);
    	}
    	
    	return liveEps;
    }

    /**
     * This method returns the N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param key - key for which we need to find the endpoint return value -
     * the endpoint responsible for this key
     */
    public Map<EndPoint, EndPoint> getHintedStorageEndpointMap(String key, EndPoint[] naturalEndpoints)
    {
        return replicationStrategy_.getHintedStorageEndPoints(partitioner_.getToken(key), naturalEndpoints);
    }

    /**
     * This function finds the most suitable endpoint given a key.
     * It checks for locality and alive test.
     */
	public EndPoint findSuitableEndPoint(String key) throws IOException, UnavailableException
	{
		EndPoint[] endpoints = getReadStorageEndPoints(key);
		for(EndPoint endPoint: endpoints)
		{
			if(endPoint.equals(StorageService.getLocalStorageEndPoint()))
			{
				return endPoint;
			}
		}
		int j = 0;
		for ( ; j < endpoints.length; ++j )
		{
			if ( StorageService.instance().isInSameDataCenter(endpoints[j]) && FailureDetector.instance().isAlive(endpoints[j]))
			{
				return endpoints[j];
			}
		}
		// We have tried to be really nice but looks like there are no servers 
		// in the local data center that are alive and can service this request so 
		// just send it to the first alive guy and see if we get anything.
		j = 0;
		for ( ; j < endpoints.length; ++j )
		{
			if ( FailureDetector.instance().isAlive(endpoints[j]))
			{
				if (logger_.isDebugEnabled())
				  logger_.debug("EndPoint " + endpoints[j] + " is alive so get data from it.");
				return endpoints[j];
			}
		}

        throw new UnavailableException(); // no nodes that could contain key are alive
	}

	Map<Token, EndPoint> getLiveEndPointMap()
	{
	    return tokenMetadata_.cloneTokenEndPointMap();
	}

    public void setLog4jLevel(String classQualifier, String rawLevel)
    {
        Level level = Level.toLevel(rawLevel);
        Logger.getLogger(classQualifier).setLevel(level);
        logger_.info("set log level to " + level + " for classes under '" + classQualifier + "' (if the level doesn't look like '" + rawLevel + "' then log4j couldn't parse '" + rawLevel + "')");
    }

    /**
     * @param splits: number of ranges to break into. Minimum 2.
     * @return list of Tokens (_not_ keys!) breaking up the data this node is responsible for into `splits` pieces.
     * There will be 1 more token than splits requested.  So for splits of 2, tokens T1 T2 T3 will be returned,
     * where (T1, T2] is the first range and (T2, T3] is the second.  The first token will always be the left
     * Token of this node's primary range, and the last will always be the Right token of that range.
     */ 
    public List<String> getSplits(int splits)
    {
        assert splits > 1;
        // we use the actual Range token for the first and last brackets of the splits to ensure correctness
        // (we're only operating on 1/128 of the keys remember)
        Range range = getLocalPrimaryRange();
        List<String> tokens = new ArrayList<String>();
        tokens.add(range.left().toString());

        List<DecoratedKey> decoratedKeys = SSTableReader.getIndexedDecoratedKeys();
        for (int i = 1; i < splits; i++)
        {
            int index = i * (decoratedKeys.size() / splits);
            tokens.add(decoratedKeys.get(index).token.toString());
        }

        tokens.add(range.right().toString());
        return tokens;
    }
}
