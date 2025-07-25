/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.MaterializedViewDefinition;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.analyzer.Analyzer;
import com.facebook.presto.sql.analyzer.MaterializedViewColumnMappingExtractor;
import com.facebook.presto.sql.analyzer.SemanticException;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.CreateMaterializedView;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.Parameter;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.util.concurrent.ListenableFuture;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.metadata.MetadataUtil.createQualifiedObjectName;
import static com.facebook.presto.metadata.MetadataUtil.getConnectorIdOrThrow;
import static com.facebook.presto.metadata.MetadataUtil.toSchemaTableName;
import static com.facebook.presto.spi.StandardErrorCode.ALREADY_EXISTS;
import static com.facebook.presto.sql.NodeUtils.mapFromProperties;
import static com.facebook.presto.sql.SqlFormatterUtil.getFormattedSql;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MATERIALIZED_VIEW_ALREADY_EXISTS;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.sql.analyzer.utils.ParameterUtils.parameterExtractor;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.Objects.requireNonNull;

public class CreateMaterializedViewTask
        implements DDLDefinitionTask<CreateMaterializedView>
{
    private final SqlParser sqlParser;

    @Inject
    public CreateMaterializedViewTask(SqlParser sqlParser)
    {
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
    }

    @Override
    public String getName()
    {
        return "CREATE MATERIALIZED VIEW";
    }

    @Override
    public ListenableFuture<?> execute(CreateMaterializedView statement, TransactionManager transactionManager, Metadata metadata, AccessControl accessControl, Session session, List<Expression> parameters, WarningCollector warningCollector, String query)
    {
        QualifiedObjectName viewName = createQualifiedObjectName(session, statement, statement.getName(), metadata);

        Optional<TableHandle> viewHandle = metadata.getMetadataResolver(session).getTableHandle(viewName);
        if (viewHandle.isPresent()) {
            if (!statement.isNotExists()) {
                throw new SemanticException(MATERIALIZED_VIEW_ALREADY_EXISTS, statement, "Materialized view '%s' already exists", viewName);
            }
            return immediateFuture(null);
        }

        accessControl.checkCanCreateTable(session.getRequiredTransactionId(), session.getIdentity(), session.getAccessControlContext(), viewName);
        accessControl.checkCanCreateView(session.getRequiredTransactionId(), session.getIdentity(), session.getAccessControlContext(), viewName);

        Map<NodeRef<Parameter>, Expression> parameterLookup = parameterExtractor(statement, parameters);
        Analyzer analyzer = new Analyzer(session, metadata, sqlParser, accessControl, Optional.empty(), parameters, parameterLookup, warningCollector, query);
        Analysis analysis = analyzer.analyze(statement);

        List<ColumnMetadata> columnMetadata = analysis.getOutputDescriptor(statement.getQuery())
                .getVisibleFields().stream()
                .map(field -> ColumnMetadata.builder()
                        .setName(metadata.normalizeIdentifier(session, viewName.getCatalogName(), field.getName().get()))
                        .setType(field.getType())
                        .build())
                .collect(toImmutableList());

        Map<String, Expression> sqlProperties = mapFromProperties(statement.getProperties());
        Map<String, Object> properties = metadata.getTablePropertyManager().getProperties(
                getConnectorIdOrThrow(session, metadata, viewName.getCatalogName()),
                viewName.getCatalogName(),
                sqlProperties,
                session,
                metadata,
                parameterLookup);

        ConnectorTableMetadata viewMetadata = new ConnectorTableMetadata(
                toSchemaTableName(viewName),
                columnMetadata,
                properties,
                statement.getComment());

        String sql = getFormattedSql(statement.getQuery(), sqlParser, Optional.of(parameters));

        List<SchemaTableName> baseTables = analysis.getTableNodes().stream()
                .map(table -> {
                    QualifiedObjectName tableName = createQualifiedObjectName(session, table, table.getName(), metadata);
                    if (!viewName.getCatalogName().equals(tableName.getCatalogName())) {
                        throw new SemanticException(
                                NOT_SUPPORTED,
                                statement,
                                "Materialized view %s created from a base table in a different catalog %s is not supported.",
                                viewName, tableName);
                    }
                    return toSchemaTableName(tableName);
                })
                .distinct()
                .collect(toImmutableList());

        MaterializedViewColumnMappingExtractor extractor = new MaterializedViewColumnMappingExtractor(analysis, session, metadata);
        MaterializedViewDefinition viewDefinition = new MaterializedViewDefinition(
                sql,
                viewName.getSchemaName(),
                viewName.getObjectName(),
                baseTables,
                Optional.of(session.getUser()),
                extractor.getMaterializedViewColumnMappings(),
                extractor.getMaterializedViewDirectColumnMappings(),
                extractor.getBaseTablesOnOuterJoinSide(),
                Optional.empty());
        try {
            metadata.createMaterializedView(session, viewName.getCatalogName(), viewMetadata, viewDefinition, statement.isNotExists());
        }
        catch (PrestoException e) {
            // connectors are not required to handle the ignoreExisting flag
            if (!e.getErrorCode().equals(ALREADY_EXISTS.toErrorCode()) || !statement.isNotExists()) {
                throw e;
            }
        }

        return immediateFuture(null);
    }

    @Override
    public String explain(CreateMaterializedView statement, List<Expression> parameters)
    {
        return "CREATE MATERIALIZED VIEW " + statement.getName();
    }
}
