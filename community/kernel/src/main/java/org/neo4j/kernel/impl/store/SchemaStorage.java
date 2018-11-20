/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.kernel.impl.store;

import java.util.Collection;
import java.util.Iterator;
import java.util.function.Predicate;

import org.neo4j.function.Predicates;
import org.neo4j.helpers.collection.PrefetchingIterator;
import org.neo4j.internal.kernel.api.exceptions.schema.MalformedSchemaRuleException;
import org.neo4j.kernel.api.exceptions.schema.DuplicateSchemaRuleException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.SchemaRule;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.StoreIndexDescriptor;
import org.neo4j.kernel.impl.store.record.ConstraintRule;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.storageengine.api.schema.ConstraintDescriptor;
import org.neo4j.storageengine.api.schema.IndexDescriptor;

public class SchemaStorage implements SchemaRuleAccess
{
    private final RecordStore<DynamicRecord> schemaStore;

    public SchemaStorage( RecordStore<DynamicRecord> schemaStore )
    {
        this.schemaStore = schemaStore;
    }

    @Override
    public long newRuleId()
    {
        return schemaStore.nextId();
    }

    @Override
    public SchemaRule loadSingleSchemaRule( long ruleId ) throws MalformedSchemaRuleException
    {
        Collection<DynamicRecord> records;
        try
        {
            records = schemaStore.getRecords( ruleId, RecordLoad.NORMAL );
        }
        catch ( Exception e )
        {
            throw new MalformedSchemaRuleException( e.getMessage(), e );
        }
        return SchemaStore.readSchemaRule( ruleId, records, newRecordBuffer() );
    }

    @Override
    public Iterator<StoreIndexDescriptor> indexesGetAll()
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), StoreIndexDescriptor.class, false );
    }

    @Override
    public StoreIndexDescriptor indexGetForSchema( final IndexDescriptor index )
    {
        Iterator<StoreIndexDescriptor> indexes = loadAllSchemaRules( index::equals, StoreIndexDescriptor.class, false );

        StoreIndexDescriptor foundRule = null;

        while ( indexes.hasNext() )
        {
            StoreIndexDescriptor candidate = indexes.next();
            if ( foundRule != null )
            {
                throw new IllegalStateException( String.format(
                        "Found more than one matching index, %s and %s", foundRule, candidate ) );
            }
            foundRule = candidate;
        }

        return foundRule;
    }

    @Override
    public ConstraintRule constraintsGetSingle( final ConstraintDescriptor descriptor )
            throws SchemaRuleNotFoundException, DuplicateSchemaRuleException
    {
        Iterator<ConstraintRule> rules = loadAllSchemaRules( descriptor::isSame, ConstraintRule.class, false );

        if ( !rules.hasNext() )
        {
            throw new SchemaRuleNotFoundException( SchemaRule.Kind.map( descriptor ), descriptor.schema() );
        }

        ConstraintRule rule = rules.next();

        if ( rules.hasNext() )
        {
            throw new DuplicateSchemaRuleException( SchemaRule.Kind.map( descriptor ), descriptor.schema() );
        }
        return rule;
    }

    @Override
    public Iterator<ConstraintRule> constraintsGetAllIgnoreMalformed()
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), ConstraintRule.class, true );
    }

    Iterator<SchemaRule> loadAllSchemaRules()
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), SchemaRule.class, false );
    }

    /**
     * Scans the schema store and loads all {@link SchemaRule rules} in it. This method is written with the assumption
     * that there's no id reuse on schema records.
     *
     * @param predicate filter when loading.
     * @param returnType type of {@link SchemaRule} to load.
     * @param ignoreMalformed whether or not to ignore inconsistent records (used in consistency checking).
     * @return {@link Iterator} of the loaded schema rules, lazily loaded when advancing the iterator.
     */
    <ReturnType extends SchemaRule> Iterator<ReturnType> loadAllSchemaRules(
            final Predicate<ReturnType> predicate,
            final Class<ReturnType> returnType,
            final boolean ignoreMalformed )
    {
        return new PrefetchingIterator<ReturnType>()
        {
            private final long highestId = schemaStore.getHighestPossibleIdInUse();
            private long currentId = 1; /*record 0 contains the block size*/
            private final byte[] scratchData = newRecordBuffer();
            private final DynamicRecord record = schemaStore.newRecord();

            @Override
            protected ReturnType fetchNextOrNull()
            {
                while ( currentId <= highestId )
                {
                    long id = currentId++;
                    schemaStore.getRecord( id, record, RecordLoad.FORCE );
                    if ( record.inUse() && record.isStartRecord() )
                    {
                        // It may be that concurrently to our reading there's a transaction dropping the schema rule
                        // that we're reading and that rule may have spanned multiple dynamic records.
                        try
                        {
                            Collection<DynamicRecord> records;
                            try
                            {
                                records = schemaStore.getRecords( id, RecordLoad.NORMAL );
                            }
                            catch ( InvalidRecordException e )
                            {
                                // This may have been due to a concurrent drop of this rule.
                                continue;
                            }

                            SchemaRule schemaRule = SchemaStore.readSchemaRule( id, records, scratchData );
                            if ( returnType.isInstance( schemaRule ) )
                            {
                                ReturnType returnRule = returnType.cast( schemaRule );
                                if ( predicate.test( returnRule ) )
                                {
                                    return returnRule;
                                }
                            }
                        }
                        catch ( MalformedSchemaRuleException e )
                        {
                            if ( !ignoreMalformed )
                            {
                                throw new RuntimeException( e );
                            }
                        }
                    }
                }
                return null;
            }
        };
    }

    private byte[] newRecordBuffer()
    {
        return new byte[schemaStore.getRecordSize() * 4];
    }
}
