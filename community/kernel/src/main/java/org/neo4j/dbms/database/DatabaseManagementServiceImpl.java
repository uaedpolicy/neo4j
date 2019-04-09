/*
 * Copyright (c) 2002-2019 "Neo4j,"
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
package org.neo4j.dbms.database;

import java.util.List;
import java.util.stream.Collectors;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.availability.CompositeDatabaseAvailabilityGuard;
import org.neo4j.kernel.database.DatabaseId;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.Logger;

public class DatabaseManagementServiceImpl implements DatabaseManagementService
{
    private final DatabaseManager<?> databaseManager;
    private final CompositeDatabaseAvailabilityGuard globalAvailabilityGuard;
    private final Lifecycle globalLife;
    private final Logger logger;

    public DatabaseManagementServiceImpl( DatabaseManager<?> databaseManager, CompositeDatabaseAvailabilityGuard globalAvailabilityGuard, Lifecycle globalLife,
            Logger logger )
    {
        this.databaseManager = databaseManager;
        this.globalAvailabilityGuard = globalAvailabilityGuard;
        this.globalLife = globalLife;
        this.logger = logger;
    }

    @Override
    public GraphDatabaseService database( String name ) throws DatabaseNotFoundException
    {
        return databaseManager.getDatabaseContext( new DatabaseId( name ) ).orElseThrow( () -> new DatabaseNotFoundException( name ) ).databaseFacade();
    }

    @Override
    public void createDatabase( String name )
    {
        databaseManager.createDatabase( new DatabaseId( name ) );
    }

    @Override
    public void dropDatabase( String name )
    {
        databaseManager.dropDatabase( new DatabaseId( name ) );
    }

    @Override
    public void startDatabase( String name )
    {
        databaseManager.startDatabase( new DatabaseId( name ) );
    }

    @Override
    public void stopDatabase( String name )
    {
        databaseManager.stopDatabase( new DatabaseId( name ) );
    }

    @Override
    public List<String> listDatabases()
    {
        return databaseManager.registeredDatabases().keySet().stream().map( DatabaseId::name ).sorted().collect( Collectors.toList() );
    }

    @Override
    public void shutdown()
    {
        try
        {
            logger.log( "Shutdown started" );
            globalAvailabilityGuard.shutdown();
            globalLife.shutdown();
        }
        catch ( Exception throwable )
        {
            logger.log( "Shutdown failed", throwable );
            throw new RuntimeException( throwable );
        }

    }
}