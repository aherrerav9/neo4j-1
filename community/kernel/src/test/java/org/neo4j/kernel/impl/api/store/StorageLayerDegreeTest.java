/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.store;

import org.junit.Rule;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.kernel.impl.core.RelationshipTypeTokenHolder;
import org.neo4j.kernel.impl.core.TokenNotFoundException;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.RecordStorageEngine;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.RecordCursors;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.kernel.impl.store.record.RelationshipGroupRecord;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;
import org.neo4j.storageengine.api.NodeItem;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.rule.RandomRule;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.neo4j.helpers.collection.Iterators.asSet;
import static org.neo4j.kernel.impl.api.store.TestRelType.IN;
import static org.neo4j.kernel.impl.api.store.TestRelType.LOOP;
import static org.neo4j.kernel.impl.api.store.TestRelType.OUT;
import static org.neo4j.kernel.impl.core.TokenHolder.NO_ID;
import static org.neo4j.kernel.impl.locking.LockService.NO_LOCK_SERVICE;
import static org.neo4j.kernel.impl.store.record.Record.NO_NEXT_RELATIONSHIP;

public class StorageLayerDegreeTest extends StorageLayerTest
{
    private static final int RELATIONSHIPS_COUNT = 20;

    @Rule
    public final RandomRule random = new RandomRule();

    protected GraphDatabaseService createGraphDatabase()
    {
        return new TestGraphDatabaseFactory().newImpermanentDatabaseBuilder()
                .setConfig( GraphDatabaseSettings.dense_node_threshold, String.valueOf( RELATIONSHIPS_COUNT ) )
                .newGraphDatabase();
    }

    @Test
    public void degreesForDenseNodeWithPartiallyDeletedRelGroupChain() throws Exception
    {
        testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain();

        testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain( IN );
        testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain( OUT );
        testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain( LOOP );

        testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain( IN, OUT );
        testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain( OUT, LOOP );
        testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain( IN, LOOP );

        testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain( IN, OUT, LOOP );
    }

    @Test
    public void degreesForDenseNodeWithPartiallyDeletedRelChains() throws Exception
    {
        testDegreesForDenseNodeWithPartiallyDeletedRelChains( false, false, false );

        testDegreesForDenseNodeWithPartiallyDeletedRelChains( true, false, false );
        testDegreesForDenseNodeWithPartiallyDeletedRelChains( false, true, false );
        testDegreesForDenseNodeWithPartiallyDeletedRelChains( false, false, true );

        testDegreesForDenseNodeWithPartiallyDeletedRelChains( true, true, false );
        testDegreesForDenseNodeWithPartiallyDeletedRelChains( true, true, true );
        testDegreesForDenseNodeWithPartiallyDeletedRelChains( true, false, true );

        testDegreesForDenseNodeWithPartiallyDeletedRelChains( true, true, true );
    }

    private void testDegreesForDenseNodeWithPartiallyDeletedRelGroupChain( TestRelType... typesToDelete )
            throws Exception
    {
        int inRelCount = randomRelCount();
        int outRelCount = randomRelCount();
        int loopRelCount = randomRelCount();

        long nodeId = createNode( inRelCount, outRelCount, loopRelCount );
        StoreSingleNodeCursor cursor = newCursor( nodeId );

        for ( TestRelType type : typesToDelete )
        {
            markRelGroupNotInUse( nodeId, type );
            switch ( type )
            {
            case IN:
                inRelCount = 0;
                break;
            case OUT:
                outRelCount = 0;
                break;
            case LOOP:
                loopRelCount = 0;
                break;
            default:
                throw new IllegalArgumentException( "Unknown type: " + type );
            }
        }

        Set<TestDegreeItem> expectedDegrees = new HashSet<>();
        if ( outRelCount != 0 )
        {
            expectedDegrees.add( new TestDegreeItem( relTypeId( OUT ), outRelCount, 0 ) );
        }
        if ( inRelCount != 0 )
        {
            expectedDegrees.add( new TestDegreeItem( relTypeId( IN ), 0, inRelCount ) );
        }
        if ( loopRelCount != 0 )
        {
            expectedDegrees.add( new TestDegreeItem( relTypeId( LOOP ), loopRelCount, loopRelCount ) );
        }

        Set<TestDegreeItem> actualDegrees = degrees( cursor );

        assertEquals( expectedDegrees, actualDegrees );
    }

    private void testDegreesForDenseNodeWithPartiallyDeletedRelChains( boolean modifyInChain, boolean modifyOutChain,
            boolean modifyLoopChain )
    {
        int inRelCount = randomRelCount();
        int outRelCount = randomRelCount();
        int loopRelCount = randomRelCount();

        long nodeId = createNode( inRelCount, outRelCount, loopRelCount );
        StoreSingleNodeCursor cursor = newCursor( nodeId );

        if ( modifyInChain )
        {
            markRandomRelsInGroupNotInUse( nodeId, IN );
        }
        if ( modifyOutChain )
        {
            markRandomRelsInGroupNotInUse( nodeId, OUT );
        }
        if ( modifyLoopChain )
        {
            markRandomRelsInGroupNotInUse( nodeId, LOOP );
        }

        Set<TestDegreeItem> expectedDegrees = new HashSet<>(
                asList( new TestDegreeItem( relTypeId( OUT ), outRelCount, 0 ),
                        new TestDegreeItem( relTypeId( IN ), 0, inRelCount ),
                        new TestDegreeItem( relTypeId( LOOP ), loopRelCount, loopRelCount ) ) );

        Set<TestDegreeItem> actualDegrees = degrees( cursor.get() );

        assertEquals( expectedDegrees, actualDegrees );
    }

    private Set<TestDegreeItem> degrees( NodeItem nodeItem )
    {
        Set<TestDegreeItem> degrees = new HashSet<>();
        disk.degrees( disk.newStatement(), nodeItem,
                ( type, outgoing, incoming ) -> degrees.add( new TestDegreeItem( type, outgoing, incoming ) ) );
        return degrees;
    }

    @SuppressWarnings( "unchecked" )
    private StoreSingleNodeCursor newCursor( long nodeId )
    {
        StoreSingleNodeCursor cursor =
                new StoreSingleNodeCursor( new NodeRecord( -1 ), resolveNeoStores(), mock( Consumer.class ),
                        new RecordCursors( resolveNeoStores() ), NO_LOCK_SERVICE );

        cursor.init( nodeId );
        assertTrue( cursor.next() );

        return cursor;
    }

    private void markRandomRelsInGroupNotInUse( long nodeId, TestRelType type )
    {
        NodeRecord node = getNodeRecord( nodeId );
        assertTrue( node.isDense() );

        long relGroupId = node.getNextRel();
        while ( relGroupId != NO_NEXT_RELATIONSHIP.intValue() )
        {
            RelationshipGroupRecord relGroup = getRelGroupRecord( relGroupId );

            if ( type == relTypeForId( relGroup.getType() ) )
            {
                markRandomRelsInChainNotInUse( relGroup.getFirstOut() );
                markRandomRelsInChainNotInUse( relGroup.getFirstIn() );
                markRandomRelsInChainNotInUse( relGroup.getFirstLoop() );
                return;
            }

            relGroupId = relGroup.getNext();
        }

        throw new IllegalStateException( "No relationship group with type: " + type + " found" );
    }

    private void markRandomRelsInChainNotInUse( long relId )
    {
        if ( relId != NO_NEXT_RELATIONSHIP.intValue() )
        {
            RelationshipRecord record = getRelRecord( relId );

            boolean shouldBeMarked = random.nextBoolean();
            if ( shouldBeMarked )
            {
                record.setInUse( false );
                update( record );
            }

            markRandomRelsInChainNotInUse( record.getFirstNextRel() );
            boolean isLoopRelationship = record.getFirstNextRel() == record.getSecondNextRel();
            if ( !isLoopRelationship )
            {
                markRandomRelsInChainNotInUse( record.getSecondNextRel() );
            }
        }
    }

    private void markRelGroupNotInUse( long nodeId, TestRelType... types )
    {
        NodeRecord node = getNodeRecord( nodeId );
        assertTrue( node.isDense() );

        Set<TestRelType> typesToRemove = asSet( types );

        long relGroupId = node.getNextRel();
        while ( relGroupId != NO_NEXT_RELATIONSHIP.intValue() )
        {
            RelationshipGroupRecord relGroup = getRelGroupRecord( relGroupId );
            TestRelType type = relTypeForId( relGroup.getType() );

            if ( typesToRemove.contains( type ) )
            {
                relGroup.setInUse( false );
                update( relGroup );
            }

            relGroupId = relGroup.getNext();
        }
    }

    private int relTypeId( TestRelType type )
    {
        DependencyResolver resolver = db.getDependencyResolver();
        RelationshipTypeTokenHolder relTypeHolder = resolver.resolveDependency( RelationshipTypeTokenHolder.class );
        int id = relTypeHolder.getIdByName( type.name() );
        assertNotEquals( NO_ID, id );
        return id;
    }

    private long createNode( int inRelCount, int outRelCount, int loopRelCount )
    {
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            for ( int i = 0; i < inRelCount; i++ )
            {
                Node start = db.createNode();
                start.createRelationshipTo( node, IN );
            }
            for ( int i = 0; i < outRelCount; i++ )
            {
                Node end = db.createNode();
                node.createRelationshipTo( end, OUT );
            }
            for ( int i = 0; i < loopRelCount; i++ )
            {
                node.createRelationshipTo( node, LOOP );
            }
            tx.success();
        }
        return node.getId();
    }

    private TestRelType relTypeForId( int id )
    {
        DependencyResolver resolver = db.getDependencyResolver();
        RelationshipTypeTokenHolder relTypeHolder = resolver.resolveDependency( RelationshipTypeTokenHolder.class );
        try
        {
            String typeName = relTypeHolder.getTokenById( id ).name();
            return TestRelType.valueOf( typeName );
        }
        catch ( TokenNotFoundException e )
        {
            throw new RuntimeException( e );
        }
    }

    private static <R extends AbstractBaseRecord> R getRecord( RecordStore<R> store, long id )
    {
        return RecordStore.getRecord( store, id, RecordLoad.FORCE );
    }

    private NodeRecord getNodeRecord( long id )
    {
        return getRecord( resolveNeoStores().getNodeStore(), id );
    }

    private RelationshipRecord getRelRecord( long id )
    {
        return getRecord( resolveNeoStores().getRelationshipStore(), id );
    }

    private RelationshipGroupRecord getRelGroupRecord( long id )
    {
        return getRecord( resolveNeoStores().getRelationshipGroupStore(), id );
    }

    private void update( RelationshipGroupRecord record )
    {
        resolveNeoStores().getRelationshipGroupStore().updateRecord( record );
    }

    private void update( RelationshipRecord record )
    {
        resolveNeoStores().getRelationshipStore().updateRecord( record );
    }

    private NeoStores resolveNeoStores()
    {
        DependencyResolver resolver = db.getDependencyResolver();
        RecordStorageEngine storageEngine = resolver.resolveDependency( RecordStorageEngine.class );
        return storageEngine.testAccessNeoStores();
    }

    private int randomRelCount()
    {
        return RELATIONSHIPS_COUNT + random.nextInt( 20 );
    }

}
