package de.bwaldvogel.mongo.backend.postgresql;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

import de.bwaldvogel.mongo.backend.AbstractMongoCollection;
import de.bwaldvogel.mongo.bson.Document;
import de.bwaldvogel.mongo.exception.MongoServerException;

public class PostgresqlCollection extends AbstractMongoCollection<Long> {

    private final PostgresqlBackend backend;

    public PostgresqlCollection(PostgresqlBackend backend, String databaseName, String collectionName, String idField) {
        super(databaseName, collectionName, idField);
        this.backend = backend;
    }

    @Override
    public int count() throws MongoServerException {
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM " + getQualifiedTablename())
        ) {
            try (ResultSet resultSet = stmt.executeQuery()) {
                if (!resultSet.next()) {
                    throw new MongoServerException("got no result");
                }
                int count = resultSet.getInt(1);
                if (resultSet.next()) {
                    throw new MongoServerException("got more than one result");
                }
                return count;
            }
        } catch (SQLException e) {
            throw new MongoServerException("failed to count " + this, e);
        }
    }

    @Override
    public void drop() throws MongoServerException {
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt = connection.prepareStatement("DROP TABLE " + getQualifiedTablename())
        ) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new MongoServerException("failed to drop " + this, e);
        }
    }

    @Override
    protected Iterable<Document> matchDocuments(Document query, Document orderBy, int numberToSkip, int numberToReturn) throws MongoServerException {
        Collection<Document> matchedDocuments = new ArrayList<>();

        int numMatched = 0;

        String sql = "SELECT data FROM " + getQualifiedTablename() + " " + convertOrderByToSql(orderBy);
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)
        ) {
            try (ResultSet resultSet = stmt.executeQuery()) {
                while (resultSet.next()) {
                    String data = resultSet.getString("data");
                    Document document = JsonConverter.fromJson(data);
                    if (documentMatchesQuery(document, query)) {
                        numMatched++;
                        if (numberToSkip <= 0 || numMatched > numberToSkip) {
                            matchedDocuments.add(document);
                        }
                        if (numberToReturn > 0 && matchedDocuments.size() == numberToReturn) {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                throw new MongoServerException("failed to parse document", e);
            }
        } catch (SQLException e) {
            throw new MongoServerException("failed to query " + this, e);
        }

        return matchedDocuments;
    }

    static String convertOrderByToSql(Document orderBy) {
        StringBuilder orderBySql = new StringBuilder();
        if (orderBy != null && !orderBy.isEmpty()) {
            orderBySql.append("ORDER BY");
            int num = 0;
            for (String key : orderBy.keySet()) {
                if (num > 0) {
                    orderBySql.append(",");
                }
                int sortValue = getSortValue(orderBy, key);
                orderBySql.append(" ");
                if (key.equals("$natural")) {
                    orderBySql.append("id");
                } else {
                    orderBySql.append(PostgresqlUtils.toDataKey(key));
                }
                if (sortValue == 1) {
                    orderBySql.append(" ASC");
                } else if (sortValue == -1) {
                    orderBySql.append(" DESC");
                } else {
                    throw new IllegalArgumentException("Illegal sort value: " + sortValue);
                }
                orderBySql.append(" NULLS LAST");
                num++;
            }
        }
        return orderBySql.toString();
    }

    private static int getSortValue(Document orderBy, String key) {
        Object orderByValue = orderBy.get(key);
        try {
            return ((Integer) orderByValue).intValue();
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Illegal sort value: " + orderByValue);
        }
    }

    @Override
    protected Iterable<Document> matchDocuments(Document query, Iterable<Long> positions, Document orderBy, int numberToSkip, int numberToReturn) throws MongoServerException {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    protected Document getDocument(Long position) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    protected void updateDataSize(long sizeDelta) throws MongoServerException {
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt = connection.prepareStatement("UPDATE " + getDatabaseName() + "._meta" +
                 " SET datasize = datasize + ? WHERE collection_name = ?")
        ) {
            stmt.setLong(1, sizeDelta);
            stmt.setString(2, getCollectionName());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new MongoServerException("failed to update datasize", e);
        }
    }

    @Override
    protected long getDataSize() throws MongoServerException {
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT datasize FROM " + getDatabaseName() + "._meta" +
                 " WHERE collection_name = ?")
        ) {
            stmt.setString(1, getCollectionName());
            return querySingleValue(stmt);
        } catch (SQLException e) {
            throw new MongoServerException("failed to retrieve datasize", e);
        }
    }

    private long querySingleValue(PreparedStatement stmt) throws SQLException, MongoServerException {
        try (ResultSet resultSet = stmt.executeQuery()) {
            if (!resultSet.next()) {
                throw new MongoServerException("got no value");
            }
            long value = resultSet.getLong(1);
            if (resultSet.next()) {
                throw new MongoServerException("got more than one value");
            }
            return Long.valueOf(value);
        }
    }

    @Override
    protected Long addDocumentInternal(Document document) throws MongoServerException {
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt = connection.prepareStatement("INSERT INTO " + getQualifiedTablename() +
                 " (data) VALUES (?::json)" +
                 " RETURNING ID")
        ) {
            String documentAsJson = JsonConverter.toJson(document);
            stmt.setString(1, documentAsJson);
            return querySingleValue(stmt);
        } catch (SQLException e) {
            throw new MongoServerException("failed to insert " + document, e);
        } catch (IOException e) {
            throw new MongoServerException("failed to serialize " + document, e);
        }
    }

    private String getQualifiedTablename() {
        return getQualifiedTablename(getDatabaseName(), getCollectionName());
    }

    public static String getQualifiedTablename(String databaseName, String collectionName) {
        return "\"" + PostgresqlDatabase.getSchemaName(databaseName) + "\".\"" + getTablename(collectionName) + "\"";
    }

    static String getTablename(String collectionName) {
        if (!collectionName.matches("^[a-zA-Z0-9_.-]+$")) {
            throw new IllegalArgumentException("Illegal database name: " + collectionName);
        }
        return collectionName.replaceAll("\\.", "_");
    }

    @Override
    protected void removeDocument(Long position) throws MongoServerException {
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt = connection.prepareStatement("DELETE FROM " + getQualifiedTablename() + " WHERE id = ?")) {
            stmt.setLong(1, position);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new MongoServerException("failed to remove document from " + this, e);
        }
    }

    @Override
    protected Long findDocumentPosition(Document document) throws MongoServerException {
        if (document.containsKey(idField)) {
            String sql = "SELECT id FROM " + getQualifiedTablename() + " WHERE " + PostgresqlUtils.toDataKey(idField) + " = ?";
            try (Connection connection = backend.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, PostgresqlUtils.toQueryValue(document.get(idField)));
                try (ResultSet resultSet = stmt.executeQuery()) {
                    if (!resultSet.next()) {
                        return null;
                    }
                    long id = resultSet.getLong(1);
                    if (resultSet.next()) {
                        throw new MongoServerException("got more than one id");
                    }
                    return Long.valueOf(id);
                }
            } catch (SQLException | IOException e) {
                throw new MongoServerException("failed to find document position of " + document, e);
            }
        } else {
            throw new UnsupportedOperationException("not yet implemented");
        }
    }

    @Override
    protected int getRecordCount() {
        return 0;
    }

    @Override
    protected int getDeletedCount() {
        return 0;
    }

    @Override
    protected void handleUpdate(Document document) throws MongoServerException {
        String sql = "UPDATE " + getQualifiedTablename() + " SET data = ?::json WHERE " + PostgresqlUtils.toDataKey(idField) + " = ?";
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, JsonConverter.toJson(document));
            Object idValue = document.get(idField);
            stmt.setString(2, PostgresqlUtils.toQueryValue(idValue));
            stmt.executeUpdate();
        } catch (SQLException | IOException e) {
            throw new MongoServerException("failed to update document in " + this, e);
        }
    }

    @Override
    public void renameTo(String newDatabaseName, String newCollectionName) throws MongoServerException {
        String oldTablename = PostgresqlCollection.getTablename(getCollectionName());
        String newTablename = PostgresqlCollection.getTablename(newCollectionName);
        try (Connection connection = backend.getConnection();
             PreparedStatement stmt1 = connection.prepareStatement("ALTER TABLE " + getQualifiedTablename() + " RENAME CONSTRAINT \"pk_" + oldTablename + "\" TO \"pk_" + newTablename + "\"");
             PreparedStatement stmt2 = connection.prepareStatement("ALTER TABLE " + getQualifiedTablename() + " RENAME TO \"" + newCollectionName + "\"")
        ) {
            stmt1.executeUpdate();
            stmt2.executeUpdate();
        } catch (SQLException e) {
            throw new MongoServerException("failed to rename " + this, e);
        }

        if (!Objects.equals(getDatabaseName(), newDatabaseName)) {
            throw new UnsupportedOperationException();
        }

        super.renameTo(newDatabaseName, newCollectionName);
    }
}