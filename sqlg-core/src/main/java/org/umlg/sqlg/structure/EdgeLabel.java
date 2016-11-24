package org.umlg.sqlg.structure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.umlg.sqlg.sql.dialect.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static org.umlg.sqlg.structure.SchemaManager.*;
import static org.umlg.sqlg.structure.Topology.SQLG_SCHEMA;

/**
 * Date: 2016/09/04
 * Time: 8:49 AM
 */
public class EdgeLabel extends AbstractLabel {

    private Logger logger = LoggerFactory.getLogger(EdgeLabel.class.getName());
    //This just won't stick in my brain.
    //hand (out) ----<label>---- finger (in)
    private Set<VertexLabel> outVertexLabels = new HashSet<>();
    private Set<VertexLabel> inVertexLabels = new HashSet<>();
    private Set<VertexLabel> uncommittedOutVertexLabels = new HashSet<>();
    private Set<VertexLabel> uncommittedInVertexLabels = new HashSet<>();
    private Topology topology;

    static EdgeLabel loadSqlgSchemaEdgeLabel(String edgeLabelName, VertexLabel outVertexLabel, VertexLabel inVertexLabel, Map<String, PropertyType> properties) {
        //edges are created in the out vertex's schema.
        EdgeLabel edgeLabel = new EdgeLabel(true, edgeLabelName, outVertexLabel, inVertexLabel, properties);
        return edgeLabel;
    }

    static EdgeLabel createEdgeLabel(SqlgGraph sqlgGraph, String edgeLabelName, VertexLabel outVertexLabel, VertexLabel inVertexLabel, Map<String, PropertyType> properties) {
        //edges are created in the out vertex's schema.
        EdgeLabel edgeLabel = new EdgeLabel(false, edgeLabelName, outVertexLabel, inVertexLabel, properties);
        if (!inVertexLabel.getSchema().isSqlgSchema()) {
            edgeLabel.createEdgeTable(sqlgGraph, outVertexLabel, inVertexLabel, properties);
        }
        return edgeLabel;
    }

    static EdgeLabel loadFromDb(Topology topology, String edgeLabelName) {
        return new EdgeLabel(topology, edgeLabelName);
    }

    private EdgeLabel(boolean forSqlgSchema, String edgeLabelName, VertexLabel outVertexLabel, VertexLabel inVertexLabel, Map<String, PropertyType> properties) {
        super(edgeLabelName, properties);
        if (forSqlgSchema) {
            this.outVertexLabels.add(outVertexLabel);
            this.inVertexLabels.add(inVertexLabel);
        } else {
            this.uncommittedOutVertexLabels.add(outVertexLabel);
            this.uncommittedInVertexLabels.add(inVertexLabel);
        }
        this.topology = outVertexLabel.getSchema().getTopology();
    }

    EdgeLabel(Topology topology, String edgeLabelName) {
        super(edgeLabelName, Collections.emptyMap());
        this.topology = topology;
    }

    @Override
    protected Schema getSchema() {
        if (!this.outVertexLabels.isEmpty()) {
            VertexLabel vertexLabel = this.outVertexLabels.iterator().next();
            return vertexLabel.getSchema();
        } else if (this.topology.isWriteLockHeldByCurrentThread() && !this.uncommittedOutVertexLabels.isEmpty()) {
            VertexLabel vertexLabel = this.uncommittedOutVertexLabels.iterator().next();
            return vertexLabel.getSchema();
        } else {
            throw new IllegalStateException("BUG: no outVertexLabels present when getSchema() is called");
        }
    }

    void ensureColumnsExist(SqlgGraph sqlgGraph, Map<String, PropertyType> columns) {
        for (Map.Entry<String, PropertyType> column : columns.entrySet()) {
            if (!this.properties.containsKey(column.getKey())) {
                if (!this.uncommittedProperties.containsKey(column.getKey())) {
                    this.getSchema().getTopology().lock();
                    if (!this.uncommittedProperties.containsKey(column.getKey())) {
                        TopologyManager.addEdgeColumn(sqlgGraph, this.getSchema().getName(), EDGE_PREFIX + getLabel(), column);
                        addColumn(sqlgGraph, this.getSchema().getName(), EDGE_PREFIX + getLabel(), ImmutablePair.of(column.getKey(), column.getValue()));
                        this.uncommittedProperties.put(column.getKey(), new PropertyColumn(this, column.getKey(), column.getValue()));
                    }
                }
            }
        }
    }

    private void createEdgeTable(SqlgGraph sqlgGraph, VertexLabel outVertexLabel, VertexLabel inVertexLabel, Map<String, PropertyType> columns) {

        String schema = outVertexLabel.getSchema().getName();
        String tableName = EDGE_PREFIX + getLabel();

        SqlDialect sqlDialect = sqlgGraph.getSqlDialect();
        sqlDialect.assertTableName(tableName);
        StringBuilder sql = new StringBuilder(sqlDialect.createTableStatement());
        sql.append(sqlDialect.maybeWrapInQoutes(schema));
        sql.append(".");
        sql.append(sqlDialect.maybeWrapInQoutes(tableName));
        sql.append("(");
        sql.append(sqlDialect.maybeWrapInQoutes("ID"));
        sql.append(" ");
        sql.append(sqlDialect.getAutoIncrementPrimaryKeyConstruct());
        if (columns.size() > 0) {
            sql.append(", ");
        }
        buildColumns(sqlgGraph, columns, sql);
        sql.append(", ");
        sql.append(sqlDialect.maybeWrapInQoutes(inVertexLabel.getSchema().getName() + "." + inVertexLabel.getLabel() + SchemaManager.IN_VERTEX_COLUMN_END));
        sql.append(" ");
        sql.append(sqlDialect.getForeignKeyTypeDefinition());
        sql.append(", ");
        sql.append(sqlDialect.maybeWrapInQoutes(outVertexLabel.getSchema().getName() + "." + outVertexLabel.getLabel() + SchemaManager.OUT_VERTEX_COLUMN_END));
        sql.append(" ");
        sql.append(sqlDialect.getForeignKeyTypeDefinition());

        //foreign key definition start
        if (sqlgGraph.isImplementForeignKeys()) {
            sql.append(", ");
            sql.append("FOREIGN KEY (");
            sql.append(sqlDialect.maybeWrapInQoutes(inVertexLabel.getSchema().getName() + "." + inVertexLabel.getLabel() + SchemaManager.IN_VERTEX_COLUMN_END));
            sql.append(") REFERENCES ");
            sql.append(sqlDialect.maybeWrapInQoutes(inVertexLabel.getSchema().getName()));
            sql.append(".");
            sql.append(sqlDialect.maybeWrapInQoutes(VERTEX_PREFIX + inVertexLabel.getLabel()));
            sql.append(" (");
            sql.append(sqlDialect.maybeWrapInQoutes("ID"));
            sql.append("), ");
            sql.append(" FOREIGN KEY (");
            sql.append(sqlDialect.maybeWrapInQoutes(outVertexLabel.getSchema().getName() + "." + outVertexLabel.getLabel() + SchemaManager.OUT_VERTEX_COLUMN_END));
            sql.append(") REFERENCES ");
            sql.append(sqlDialect.maybeWrapInQoutes(outVertexLabel.getSchema().getName()));
            sql.append(".");
            sql.append(sqlDialect.maybeWrapInQoutes(VERTEX_PREFIX + outVertexLabel.getLabel()));
            sql.append(" (");
            sql.append(sqlDialect.maybeWrapInQoutes("ID"));
            sql.append(")");
        }
        //foreign key definition end

        sql.append(")");
        if (sqlDialect.needsSemicolon()) {
            sql.append(";");
        }

        if (sqlDialect.needForeignKeyIndex()) {
            sql.append("\nCREATE INDEX ON ");
            sql.append(sqlDialect.maybeWrapInQoutes(schema));
            sql.append(".");
            sql.append(sqlDialect.maybeWrapInQoutes(tableName));
            sql.append(" (");
            sql.append(sqlDialect.maybeWrapInQoutes(inVertexLabel.getSchema().getName() + "." + inVertexLabel.getLabel() + SchemaManager.IN_VERTEX_COLUMN_END));
            sql.append(");");

            sql.append("\nCREATE INDEX ON ");
            sql.append(sqlDialect.maybeWrapInQoutes(schema));
            sql.append(".");
            sql.append(sqlDialect.maybeWrapInQoutes(tableName));
            sql.append(" (");
            sql.append(sqlDialect.maybeWrapInQoutes(outVertexLabel.getSchema().getName() + "." + outVertexLabel.getLabel() + SchemaManager.OUT_VERTEX_COLUMN_END));
            sql.append(");");
        }

        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        Connection conn = sqlgGraph.tx().getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    void afterCommit() {
        super.afterCommit();
        if (this.getSchema().getTopology().isWriteLockHeldByCurrentThread()) {
            for (Iterator<VertexLabel> it = this.uncommittedInVertexLabels.iterator(); it.hasNext(); ) {
                VertexLabel vertexLabel = it.next();
                this.inVertexLabels.add(vertexLabel);
                it.remove();
            }
            for (Iterator<VertexLabel> it = this.uncommittedOutVertexLabels.iterator(); it.hasNext(); ) {
                VertexLabel vertexLabel = it.next();
                this.outVertexLabels.add(vertexLabel);
                it.remove();
            }
        }
    }

    void afterRollbackInEdges(VertexLabel vertexLabel) {
        super.afterRollback();
        this.uncommittedInVertexLabels.remove(vertexLabel);
    }

    void afterRollbackOutEdges(VertexLabel vertexLabel) {
        super.afterRollback();
        this.uncommittedOutVertexLabels.remove(vertexLabel);
    }

    @Override
    public String toString() {
        return toJson().toString();
    }

    Set<String> getAllEdgeForeignKeys() {
        Set<String> result = new HashSet<>();
        for (VertexLabel vertexLabel : this.inVertexLabels) {
            result.add(vertexLabel.getSchema().getName() + "." + vertexLabel.getLabel() + SchemaManager.IN_VERTEX_COLUMN_END);
        }
        for (VertexLabel vertexLabel : this.outVertexLabels) {
            result.add(vertexLabel.getSchema().getName() + "." + vertexLabel.getLabel() + SchemaManager.OUT_VERTEX_COLUMN_END);
        }
        if (this.getSchema().getTopology().isWriteLockHeldByCurrentThread()) {
            for (VertexLabel vertexLabel : this.uncommittedInVertexLabels) {
                result.add(vertexLabel.getSchema().getName() + "." + vertexLabel.getLabel() + SchemaManager.IN_VERTEX_COLUMN_END);
            }
            for (VertexLabel vertexLabel : this.uncommittedOutVertexLabels) {
                result.add(vertexLabel.getSchema().getName() + "." + vertexLabel.getLabel() + SchemaManager.OUT_VERTEX_COLUMN_END);
            }
        }
        return result;
    }

    Set<VertexLabel> getUncommittedOutVertexLabels() {
        return uncommittedOutVertexLabels;
    }

    Set<VertexLabel> getUncommittedInVertexLabels() {
        return uncommittedInVertexLabels;
    }

    void ensureEdgeForeignKeysExist(SqlgGraph sqlgGraph, boolean in, VertexLabel vertexLabel, SchemaTable vertexSchemaTable) {
        Preconditions.checkArgument(vertexLabel.getSchema().getName().equals(vertexSchemaTable.getSchema()));
        Preconditions.checkArgument(vertexLabel.getLabel().equals(vertexSchemaTable.getTable()));

        Set<String> allEdgeForeignKeys = getAllEdgeForeignKeys();
        SchemaTable foreignKey = SchemaTable.of(vertexSchemaTable.getSchema(), vertexSchemaTable.getTable() + (in ? SchemaManager.IN_VERTEX_COLUMN_END : SchemaManager.OUT_VERTEX_COLUMN_END));
        if (!allEdgeForeignKeys.contains(foreignKey.getSchema() + "." + foreignKey.getTable())) {
            //Make sure the current thread/transaction owns the lock
            Schema schema = this.getSchema();
            schema.getTopology().lock();
            allEdgeForeignKeys = getAllEdgeForeignKeys();
            if (!allEdgeForeignKeys.contains(foreignKey.getSchema() + "." + foreignKey.getTable())) {
                TopologyManager.addLabelToEdge(sqlgGraph, this.getSchema().getName(), EDGE_PREFIX + getLabel(), in, foreignKey);
                if (in) {
                    this.uncommittedInVertexLabels.add(vertexLabel);
                    vertexLabel.addToUncommittedInEdgeLabels(schema, this);
                } else {
                    this.uncommittedOutVertexLabels.add(vertexLabel);
                    vertexLabel.addToUncommittedOutEdgeLabels(schema, this);
                }
                addEdgeForeignKey(sqlgGraph, schema.getName(), EDGE_PREFIX + getLabel(), foreignKey, vertexSchemaTable);
            }
        }
    }

    private void addEdgeForeignKey(SqlgGraph sqlgGraph, String schema, String table, SchemaTable foreignKey, SchemaTable otherVertex) {
        Preconditions.checkState(!this.getSchema().isSqlgSchema(), "BUG: ensureEdgeForeignKeysExist may not be called for %s", SQLG_SCHEMA);
        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE ");
        sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(schema));
        sql.append(".");
        sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(table));
        sql.append(" ADD COLUMN ");
        sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(foreignKey.getSchema() + "." + foreignKey.getTable()));
        sql.append(" ");
        sql.append(sqlgGraph.getSqlDialect().getForeignKeyTypeDefinition());
        if (sqlgGraph.getSqlDialect().needsSemicolon()) {
            sql.append(";");
        }
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        Connection conn = sqlgGraph.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        sql.setLength(0);
        //foreign key definition start
        if (sqlgGraph.isImplementForeignKeys()) {
            sql.append(" ALTER TABLE ");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(schema));
            sql.append(".");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(table));
            sql.append(" ADD CONSTRAINT ");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(table + "_" + foreignKey.getSchema() + "." + foreignKey.getTable() + "_fkey"));
            sql.append(" FOREIGN KEY (");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(foreignKey.getSchema() + "." + foreignKey.getTable()));
            sql.append(") REFERENCES ");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(otherVertex.getSchema()));
            sql.append(".");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(VERTEX_PREFIX + otherVertex.getTable()));
            sql.append(" (");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes("ID"));
            sql.append(")");
            if (sqlgGraph.getSqlDialect().needsSemicolon()) {
                sql.append(";");
            }
            if (logger.isDebugEnabled()) {
                logger.debug(sql.toString());
            }
            try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
                preparedStatement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        sql.setLength(0);
        if (sqlgGraph.getSqlDialect().needForeignKeyIndex()) {
            sql.append("\nCREATE INDEX ON ");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(schema));
            sql.append(".");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(table));
            sql.append(" (");
            sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(foreignKey.getSchema() + "." + foreignKey.getTable()));
            sql.append(")");
            if (sqlgGraph.getSqlDialect().needsSemicolon()) {
                sql.append(";");
            }
            if (logger.isDebugEnabled()) {
                logger.debug(sql.toString());
            }
            try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
                preparedStatement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    void addToOutVertexLabel(VertexLabel vertexLabel) {
        this.outVertexLabels.add(vertexLabel);
    }

    void addToInVertexLabel(VertexLabel vertexLabel) {
        this.inVertexLabels.add(vertexLabel);
    }

    @Override
    public int hashCode() {
        //Edges are unique per out vertex schemas.
        //En edge must have at least one out vertex so take it to get the schema.
        VertexLabel vertexLabel;
        if (!this.outVertexLabels.isEmpty()) {
            vertexLabel = this.outVertexLabels.iterator().next();
        } else {
            vertexLabel = this.uncommittedOutVertexLabels.iterator().next();
        }
        return (vertexLabel.getSchema().getName() + this.getLabel()).hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) {
            return false;
        }
        if (!(other instanceof EdgeLabel)) {
            return false;
        }
        EdgeLabel otherEdgeLabel = (EdgeLabel) other;
        VertexLabel vertexLabel = this.outVertexLabels.iterator().next();
        VertexLabel otherVertexLabel = otherEdgeLabel.outVertexLabels.iterator().next();
        return vertexLabel.getSchema().equals(otherVertexLabel.getSchema()) && otherEdgeLabel.getLabel().equals(this.getLabel());
    }

    boolean deepEquals(EdgeLabel otherEdgeLabel) {
        Preconditions.checkState(this.equals(otherEdgeLabel), "equals must have passed before calling deepEquals");

        //check every out and in edge
        for (VertexLabel outVertexLabel : this.outVertexLabels) {
            boolean ok = false;
            for (VertexLabel otherOutVertexLabel : otherEdgeLabel.outVertexLabels) {
                if (outVertexLabel.equals(otherOutVertexLabel)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        for (VertexLabel inVertexLabel : this.inVertexLabels) {
            boolean ok = false;
            for (VertexLabel otherInVertexLabel : otherEdgeLabel.inVertexLabels) {
                if (inVertexLabel.equals(otherInVertexLabel)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected JsonNode toJson() {
        ObjectNode edgeLabelNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
        edgeLabelNode.put("schema", getSchema().getName());
        edgeLabelNode.put("label", getLabel());
        edgeLabelNode.set("properties", super.toJson());

        ArrayNode outVertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
        for (VertexLabel outVertexLabel : this.outVertexLabels) {
            ObjectNode outVertexLabelObjectNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
            outVertexLabelObjectNode.put("label", outVertexLabel.getLabel());
            outVertexLabelArrayNode.add(outVertexLabelObjectNode);
        }
        edgeLabelNode.set("outVertexLabels", outVertexLabelArrayNode);

        ArrayNode inVertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
        for (VertexLabel inVertexLabel : this.inVertexLabels) {
            ObjectNode inVertexLabelObjectNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
            inVertexLabelObjectNode.put("label", inVertexLabel.getLabel());
            inVertexLabelArrayNode.add(inVertexLabelObjectNode);
        }
        edgeLabelNode.set("inVertexLabels", inVertexLabelArrayNode);

        if (this.getSchema().getTopology().isWriteLockHeldByCurrentThread()) {
            outVertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
            for (VertexLabel outVertexLabel : this.uncommittedOutVertexLabels) {
                ObjectNode outVertexLabelObjectNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
                outVertexLabelObjectNode.put("label", outVertexLabel.getLabel());
                outVertexLabelArrayNode.add(outVertexLabelObjectNode);
            }
            edgeLabelNode.set("uncommittedOutVertexLabels", outVertexLabelArrayNode);

            inVertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
            for (VertexLabel inVertexLabel : this.uncommittedInVertexLabels) {
                ObjectNode inVertexLabelObjectNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
                inVertexLabelObjectNode.put("label", inVertexLabel.getLabel());
                inVertexLabelArrayNode.add(inVertexLabelObjectNode);
            }
            edgeLabelNode.set("uncommittedInVertexLabels", inVertexLabelArrayNode);
        }

        return edgeLabelNode;
    }

    @Override
    protected Optional<JsonNode> toNotifyJson() {

        boolean foundSomething = false;
        ObjectNode edgeLabelNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
        edgeLabelNode.put("schema", getSchema().getName());
        edgeLabelNode.put("label", getLabel());

        Optional<JsonNode> propertyNode = super.toNotifyJson();
        if (propertyNode.isPresent()) {
            foundSomething = true;
            edgeLabelNode.set("uncommittedProperties", propertyNode.get());
        }

        if (this.getSchema().getTopology().isWriteLockHeldByCurrentThread() && !this.uncommittedOutVertexLabels.isEmpty()) {
            foundSomething = true;
            ArrayNode outVertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
            for (VertexLabel outVertexLabel : this.uncommittedOutVertexLabels) {
                ObjectNode outVertexLabelObjectNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
                outVertexLabelObjectNode.put("label", outVertexLabel.getLabel());
                outVertexLabelArrayNode.add(outVertexLabelObjectNode);
            }
            edgeLabelNode.set("uncommittedOutVertexLabels", outVertexLabelArrayNode);
        }

        if (this.getSchema().getTopology().isWriteLockHeldByCurrentThread() && !this.uncommittedInVertexLabels.isEmpty()) {
            foundSomething = true;
            ArrayNode inVertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
            for (VertexLabel inVertexLabel : this.uncommittedInVertexLabels) {
                ObjectNode inVertexLabelObjectNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
                inVertexLabelObjectNode.put("label", inVertexLabel.getLabel());
                inVertexLabelArrayNode.add(inVertexLabelObjectNode);
            }
            edgeLabelNode.set("uncommittedInVertexLabels", inVertexLabelArrayNode);
        }

        if (foundSomething) {
            return Optional.of(edgeLabelNode);
        } else {
            return Optional.empty();
        }
    }

}