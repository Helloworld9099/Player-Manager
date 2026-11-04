package dev.playerinspector;

/** Current last-death event; inventory is null for migrated older history records. */
public record LastDeath(DeathRecord details, PlayerSnapshot inventory, boolean keepInventory) {}
