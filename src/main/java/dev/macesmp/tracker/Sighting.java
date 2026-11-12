package dev.macesmp.tracker;

import java.util.UUID;

/** One place where a Mace was seen during a sweep. */
final class Sighting {
    final Ref ref;
    final UUID id;      // null when the Mace has no tracker id
    final String where; // human readable, also stored as lastLocation ("player:Steve", "container:world 1 2 3", ...)
    final int priority; // lower is preferred when choosing which copy of a duplicate to keep

    Sighting(Ref ref, UUID id, String where, int priority) {
        this.ref = ref;
        this.id = id;
        this.where = where;
        this.priority = priority;
    }
}
