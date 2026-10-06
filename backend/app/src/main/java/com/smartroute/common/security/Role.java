package com.smartroute.common.security;

/** The four roles. Every user has exactly one; it is checked on the server for every request. */
public enum Role {
    /** Manages users, warehouses, vehicles and settings. */
    ADMIN,
    /** Creates and assigns orders, watches the fleet. */
    DISPATCHER,
    /** Sees and updates only their own deliveries and status. */
    DRIVER,
    /** Read-only access to orders, fleet and analytics. */
    VIEWER
}
