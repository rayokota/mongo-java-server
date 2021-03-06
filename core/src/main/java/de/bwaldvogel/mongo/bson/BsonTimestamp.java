package de.bwaldvogel.mongo.bson;

public class BsonTimestamp implements Bson {

    private static final long serialVersionUID = 1L;

    private long timestamp;

    public BsonTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    protected BsonTimestamp() {
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
