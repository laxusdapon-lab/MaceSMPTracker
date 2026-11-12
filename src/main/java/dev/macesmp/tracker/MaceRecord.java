package dev.macesmp.tracker;

import java.util.UUID;

/** One tracked Mace. Fields are only modified through {@link MaceRegistry}. */
final class MaceRecord {
    enum State { ACTIVE, DESTROYED }

    final UUID id;
    State state = State.ACTIVE;
    long created;
    String crafterUuid = "";
    String crafterName = "";
    long lastSeen;
    String lastLocation = "unknown";
    int missedAudits;
    long destroyed;
    String destroyReason = "";

    MaceRecord(UUID id) {
        this.id = id;
    }
}
