package com.conveyal.datatools.manager.utils.sql;

import com.conveyal.datatools.manager.models.FeedSource;
import com.conveyal.datatools.manager.models.FeedVersion;
import com.conveyal.datatools.manager.models.Snapshot;
import com.conveyal.datatools.manager.persistence.Persistence;
import com.conveyal.gtfs.loader.Table;
import com.conveyal.gtfs.storage.StorageException;
import com.google.common.base.Strings;
import org.bson.conversions.Bson;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.not;

/**
 * This class handles operations to detect whether postgresql tables
 * are consistent with the gtfs-lib implementation from the project dependencies,
 * and fills out missing tables/columns and changes column types if needed.
 */
public class SqlSchemaUpdater implements AutoCloseable {
    /** Maintain a list of scanned namespaces to avoid duplicate work. */
    private Map<String, NamespaceCheck> checkedNamespaces = new HashMap<>();

    private final Connection connection;
    private final PreparedStatement selectNamespaceTablesStatement;
    private final PreparedStatement selectColumnStatement;

    public SqlSchemaUpdater(Connection connection) throws SQLException {
        this.connection = connection;

        // Cache all prepared statements.

        selectColumnStatement = connection.prepareStatement(
            "select column_name, data_type from information_schema.columns where table_schema = ? and table_name = ?"
        );

        // Check that all tables for the namespace are present.
        selectNamespaceTablesStatement = connection.prepareStatement(
            "select table_name from information_schema.tables where table_schema = ?"
        );
    }


    /**
     * For all namespaces of the specified types (EDITOR, SNAPSHOTS, VERSIONS)
     * and that are referenced from projects, check for tables and columns that need to be added or changed.
     */
    public Collection<NamespaceCheck> checkReferencedNamespaces(List<String> namespaceTypesToCheck) {
        resetCheckedNamespaces();

        Persistence.projects.getAll().forEach(p -> {
            System.out.printf("Project %s%n", p.name);
            Persistence.feedSources.getFiltered(eq("projectId", p.id)).forEach(fs -> {
                System.out.printf("- FeedSource %s %s%n", fs.name, fs.id);
                if (namespaceTypesToCheck.contains("EDITOR") && !Strings.isNullOrEmpty(fs.editorNamespace)) {
                    checkTablesForNamespace(fs.editorNamespace, fs, "editor");
                }

                Bson feedSourceIdFilter = eq("feedSourceId", fs.id);
                Bson feedSourceIdNamespaceFilter = and(
                    eq("feedSourceId", fs.id),
                    not(eq("namespace", null))
                );

                if (namespaceTypesToCheck.contains("VERSIONS")) {
                    List<FeedVersion> allFeedVersions = Persistence.feedVersions.getFiltered(feedSourceIdFilter);
                    List<FeedVersion> feedVersions = Persistence.feedVersions.getFiltered(feedSourceIdNamespaceFilter);
                    System.out.printf("  - FeedVersions (%d/%d with valid namespace)%n", feedVersions.size(), allFeedVersions.size());
                    feedVersions.forEach(
                        fv -> {
                            checkTablesForNamespace(fv.namespace, fs, "v" + fv.version);
                        }
                    );
                }

                if (namespaceTypesToCheck.contains("SNAPSHOTS")) {
                    List<Snapshot> allSnapshots = Persistence.snapshots.getFiltered(feedSourceIdFilter);
                    List<Snapshot> snapshots = Persistence.snapshots.getFiltered(feedSourceIdNamespaceFilter);
                    System.out.printf("  - Snapshots (%d/%d with valid namespace)%n", snapshots.size(), allSnapshots.size());
                    snapshots.forEach(
                        sn -> {
                            checkTablesForNamespace(sn.namespace, fs, sn.name == null ? "(unnamed)" : sn.name);

                            // TODO: Consider scanning/upgrading namespaces referenced by snapshotOf (except "mapdb_editor" references).
                        }
                    );
                }
            });}
        );

        // Once done, print the SQL statements to update the tables
        printSqlChanges();

        return checkedNamespaces.values();
    }

    private void printSqlChanges() {
        System.out.println("-- Overview of changes that should be performed:");
        checkedNamespaces.values().forEach(ns -> {
            if (!ns.isOrphan()) {
                System.out.println("-- " + ns.getHeaderText());
                // Add missing tables
                ns.missingTables.forEach(t -> {
                    System.out.printf("CREATE TABLE %s.%s ... (using Table.createSqlTable)%n", ns.namespace, t.name);
                });
                // Print alter table statements
                ns.checkedTables.forEach(t -> {
                    if (t.hasColumnIssues()) {
                        System.out.println(t.getAlterTableSql());
                    }
                });
            }
        });
        System.out.println("-- End of changes");
    }

    /**
     * Clears the list of scanned namespaces (used for tests).
     */
    public void resetCheckedNamespaces() {
        checkedNamespaces = new HashMap<>();
    }

    public NamespaceCheck checkTablesForNamespace(String namespace, FeedSource feedSource, String type) {
        NamespaceCheck existingNamespaceCheck = checkedNamespaces.get(namespace);
        if (existingNamespaceCheck == null) {
            try {
                NamespaceCheck namespaceCheck = new NamespaceCheck(
                    namespace,
                    feedSource,
                    type,
                    this
                );
                namespaceCheck.printReport(type);
                checkedNamespaces.put(namespace, namespaceCheck);
                return namespaceCheck;
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return existingNamespaceCheck;
    }

    /**
     * Upgrades a namespace that is not orphan by adding missing tables/columns and
     * changing columns with incorrect types.
     */
    public void upgradeNamespaceIfNotOrphan(NamespaceCheck ns) throws SQLException, StorageException {
        if (!ns.isOrphan()) {
            String namespace = ns.namespace;

            // Add missing tables
            for (Table t : ns.missingTables) {
                t.createSqlTable(connection, namespace, true);
            }

            for (TableCheck tableCheck : ns.checkedTables) {
                // Fix column issues for a table, if any.
                if (tableCheck.hasColumnIssues()) {
                    try (Statement alterStatement = connection.createStatement()) {
                        String alterTableSql = tableCheck.getAlterTableSql();
                        System.out.println("Executing " + alterTableSql);
                        alterStatement.execute(alterTableSql);
                    }
                }
            }
        }
    }

    /**
     * Obtains the table names for a given namespace.
     */
    public List<String> getTableNames(String namespace) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        selectNamespaceTablesStatement.setString(1, namespace);
        ResultSet resultSet = selectNamespaceTablesStatement.executeQuery();
        while (resultSet.next()) {
            tableNames.add(resultSet.getString(1));
        }
        return tableNames;
    }

    public List<ColumnCheck> getColumns(String namespace, Table table) {
        List<ColumnCheck> columns = new ArrayList<>();
        try {
            selectColumnStatement.setString(1, namespace);
            selectColumnStatement.setString(2, table.name);

            ResultSet resultSet = selectColumnStatement.executeQuery();
            while (resultSet.next()) {
                columns.add(new ColumnCheck(
                    resultSet.getString(1),
                    resultSet.getString(2)
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return columns;
    }

    @Override
    public void close() throws Exception {
        selectColumnStatement.close();
        selectNamespaceTablesStatement.close();
    }
}