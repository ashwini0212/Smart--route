package com.smartroute.demo;

import com.smartroute.fleet.DriverRequest;
import com.smartroute.fleet.DriverResponse;
import com.smartroute.fleet.DriverService;
import com.smartroute.fleet.DriverStatus;
import com.smartroute.fleet.VehicleRequest;
import com.smartroute.fleet.VehicleResponse;
import com.smartroute.fleet.VehicleService;
import com.smartroute.fleet.VehicleType;
import com.smartroute.order.CreateOrderRequest;
import com.smartroute.order.OrderPriority;
import com.smartroute.order.OrderService;
import com.smartroute.warehouse.WarehouseRequest;
import com.smartroute.warehouse.WarehouseResponse;
import com.smartroute.warehouse.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates deterministic demo data when the {@code seed} profile is active and the database is empty.
 *
 * <p>All people are fictional: names are random combinations of common first names and surnames, and
 * phone numbers use the obviously fake {@code +91 90000 xxxxx} range. Everything goes through the
 * normal services, so seed data obeys the same validation as API requests.
 */
@Component
@Profile("seed")
@EnableConfigurationProperties(SeedProperties.class)
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final List<String> FIRST_NAMES = List.of("Aarav", "Vihaan", "Ananya", "Diya", "Arjun", "Ishaan",
            "Kavya", "Meera", "Rohan", "Sneha", "Kiran", "Lakshmi", "Nikhil", "Pooja", "Rahul", "Sanjana",
            "Tejas", "Uma", "Varun", "Yash", "Zoya", "Farhan", "Gauri", "Harsha");
    private static final List<String> SURNAMES = List.of("Rao", "Iyer", "Reddy", "Nair", "Sharma", "Gowda",
            "Patel", "Khan", "Das", "Menon", "Shetty", "Kulkarni", "Joshi", "Hegde", "Pillai", "Verma");
    private static final List<String> STREETS = List.of("1st Main Road", "2nd Cross", "Church Street",
            "Residency Road", "Station Road", "Lake View Road", "Temple Street", "Park Road", "Ring Road",
            "Market Road", "8th Block", "Service Road");

    private final SeedProperties properties;
    private final WarehouseService warehouses;
    private final VehicleService vehicles;
    private final DriverService drivers;
    private final OrderService orders;
    private final Clock clock;

    DemoDataSeeder(SeedProperties properties, WarehouseService warehouses, VehicleService vehicles,
                   DriverService drivers, OrderService orders, Clock clock) {
        this.properties = properties;
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.orders = orders;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (warehouses.count() > 0) {
            log.info("Seed skipped: database already has data");
            return;
        }
        Random random = new Random(properties.randomSeed());
        List<WarehouseResponse> hubs = seedWarehouses();
        List<DriverResponse> seededDrivers = seedFleet(random, hubs);
        seedOrders(random, hubs);
        log.info("Seeded {} warehouses, {} drivers with vehicles, {} orders (seed {})",
                hubs.size(), seededDrivers.size(), properties.orders(), properties.randomSeed());
    }

    private List<WarehouseResponse> seedWarehouses() {
        double midLat = (properties.minLatitude() + properties.maxLatitude()) / 2;
        double midLon = (properties.minLongitude() + properties.maxLongitude()) / 2;
        double dLat = (properties.maxLatitude() - properties.minLatitude()) / 4;
        double dLon = (properties.maxLongitude() - properties.minLongitude()) / 4;
        return List.of(
                warehouses.create(new WarehouseRequest("WH-NORTH", "North Hub", "North Hub, Outer Ring Road", midLat + dLat, midLon)),
                warehouses.create(new WarehouseRequest("WH-SOUTH", "South Hub", "South Hub, Industrial Area", midLat - dLat, midLon)),
                warehouses.create(new WarehouseRequest("WH-EAST", "East Hub", "East Hub, Logistics Park", midLat, midLon + dLon)),
                warehouses.create(new WarehouseRequest("WH-WEST", "West Hub", "West Hub, Trade Centre", midLat, midLon - dLon)));
    }

    private List<DriverResponse> seedFleet(Random random, List<WarehouseResponse> hubs) {
        List<DriverResponse> result = new ArrayList<>();
        for (int i = 1; i <= properties.drivers(); i++) {
            VehicleType type = pick(random, 0.6, 0.3);
            VehicleResponse vehicle = vehicles.create(vehicleFor(type, i));
            WarehouseResponse home = hubs.get(i % hubs.size());
            DriverResponse driver = drivers.create(new DriverRequest(fakeName(random),
                    "+91 90000 %05d".formatted(i), home.id(), vehicle.id()));
            // Start each driver within ~2 km of their home warehouse.
            drivers.updateLocation(driver.id(), home.latitude() + jitter(random, 0.018),
                    home.longitude() + jitter(random, 0.018), clock.instant());
            double roll = random.nextDouble();
            DriverStatus status = roll < 0.70 ? DriverStatus.AVAILABLE : roll < 0.85 ? DriverStatus.ON_BREAK : DriverStatus.OFFLINE;
            if (status != DriverStatus.OFFLINE) {
                drivers.changeStatus(driver.id(), status);
            }
            result.add(driver);
        }
        return result;
    }

    private void seedOrders(Random random, List<WarehouseResponse> hubs) {
        Instant now = clock.instant();
        for (int i = 0; i < properties.orders(); i++) {
            WarehouseResponse hub = hubs.get(random.nextInt(hubs.size()));
            double size = random.nextDouble();
            // 80 % parcels a bike can carry, 15 % bulky (van), 5 % freight (truck).
            BigDecimal weight = kg(size < 0.80 ? 0.2 + random.nextDouble() * 14.8
                    : size < 0.95 ? 25 + random.nextDouble() * 275 : 600 + random.nextDouble() * 1400);
            BigDecimal volume = m3(weight.doubleValue() / 180 + 0.002);
            VehicleType required = size < 0.80 ? null : size < 0.95 ? VehicleType.VAN : VehicleType.TRUCK;
            OrderPriority priority = priority(random);
            Instant windowStart = null;
            Instant windowEnd = null;
            if (random.nextDouble() < 0.4) {
                windowStart = now.plus(Duration.ofMinutes(random.nextInt(240)));
                windowEnd = windowStart.plus(Duration.ofHours(2));
            }
            orders.create(new CreateOrderRequest(hub.id(), fakeName(random),
                    (1 + random.nextInt(300)) + ", " + STREETS.get(random.nextInt(STREETS.size())) + ", Bengaluru",
                    uniform(random, properties.minLatitude(), properties.maxLatitude()),
                    uniform(random, properties.minLongitude(), properties.maxLongitude()),
                    priority, weight, volume, required, windowStart, windowEnd));
        }
    }

    private static VehicleRequest vehicleFor(VehicleType type, int index) {
        String plate = "KA01-%s-%04d".formatted(type.name().charAt(0), index);
        return switch (type) {
            case BIKE -> new VehicleRequest(plate, type, new BigDecimal("20"), new BigDecimal("0.150"));
            case VAN -> new VehicleRequest(plate, type, new BigDecimal("600"), new BigDecimal("4.000"));
            case TRUCK -> new VehicleRequest(plate, type, new BigDecimal("3000"), new BigDecimal("18.000"));
        };
    }

    private static VehicleType pick(Random random, double bikeShare, double vanShare) {
        double roll = random.nextDouble();
        return roll < bikeShare ? VehicleType.BIKE : roll < bikeShare + vanShare ? VehicleType.VAN : VehicleType.TRUCK;
    }

    private static OrderPriority priority(Random random) {
        double roll = random.nextDouble();
        return roll < 0.2 ? OrderPriority.LOW : roll < 0.7 ? OrderPriority.NORMAL : roll < 0.9 ? OrderPriority.HIGH : OrderPriority.URGENT;
    }

    private static String fakeName(Random random) {
        return FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size())) + " " + SURNAMES.get(random.nextInt(SURNAMES.size()));
    }

    private static double jitter(Random random, double max) {
        return (random.nextDouble() * 2 - 1) * max;
    }

    private static double uniform(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private static BigDecimal kg(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal m3(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }
}
