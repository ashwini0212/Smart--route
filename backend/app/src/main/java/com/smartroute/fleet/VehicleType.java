package com.smartroute.fleet;

/** Vehicle classes. An order may require a minimum class; a bigger class can always carry a smaller order's needs. */
public enum VehicleType {
    BIKE, VAN, TRUCK;

    /** True if a vehicle of this type satisfies an order that requires {@code required} (null = any). */
    public boolean satisfies(VehicleType required) {
        return required == null || this.ordinal() >= required.ordinal();
    }
}
