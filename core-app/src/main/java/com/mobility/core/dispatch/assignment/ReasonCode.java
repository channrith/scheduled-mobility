package com.mobility.core.dispatch.assignment;

/** Why a dispatcher assigned or changed a driver; every manual change needs one (OTHER also needs a note). */
public enum ReasonCode {
	PLANNED, SUGGESTION_ACCEPTED, DRIVER_UNAVAILABLE, DRIVER_LATE, VEHICLE_ISSUE, CUSTOMER_REQUEST, REBALANCE,
	BACKUP_SWITCH, OTHER
}
