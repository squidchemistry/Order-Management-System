package com.arshverma.orders.config;

import com.arshverma.orders.domain.LineItem;
import com.arshverma.orders.repository.OrderRepository;
import com.arshverma.orders.service.CreateOrderCommand;
import com.arshverma.orders.service.CreateOrderResult;
import com.arshverma.orders.service.OrderService;
import com.arshverma.orders.web.dto.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inserts a handful of orders in every status so the API has something to show on first start.
 * Runs only when app.seed.enabled=true and the orders table is empty, and goes through the service
 * so seeded data obeys the same rules as API-created data. Each seed uses a fixed Idempotency-Key,
 * so two instances starting at once cannot double-seed.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final OrderRepository orders;
    private final OrderService orderService;

    public DemoDataSeeder(OrderRepository orders, OrderService orderService) {
        this.orders = orders;
        this.orderService = orderService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (orders.count() > 0) {
            log.info("Seed skipped: orders table already has data");
            return;
        }
        CreateOrderResult first = seed("seed-1", "cust-1001",
                new LineItem("SKU-KB-01", "Mechanical keyboard", 1, new BigDecimal("3499.00")),
                new LineItem("SKU-MS-02", "Wireless mouse", 2, new BigDecimal("799.50")));
        if (first.replayed()) {
            log.info("Seed skipped: another instance is seeding");
            return;
        }
        OrderResponse o1 = first.order();
        OrderResponse o2 = seed("seed-2", "cust-1001",
                new LineItem("SKU-MN-27", "27-inch monitor", 1, new BigDecimal("15999.00"))).order();
        OrderResponse o3 = seed("seed-3", "cust-2002",
                new LineItem("SKU-HD-05", "USB-C hub", 3, new BigDecimal("1299.00"))).order();
        seed("seed-4", "cust-2002",
                new LineItem("SKU-CB-10", "HDMI cable", 4, new BigDecimal("249.99")),
                new LineItem("SKU-LS-11", "Laptop stand", 1, new BigDecimal("1899.00")));
        seed("seed-5", "cust-3003",
                new LineItem("SKU-WC-07", "1080p webcam", 1, new BigDecimal("2499.00")));

        orderService.confirm(o1.id(), null);
        orderService.confirm(o2.id(), null);
        orderService.cancel(o2.id(), "Customer found a better price", null);
        orderService.cancel(o3.id(), "Duplicate order", null);
        log.info("Seeded 5 demo orders (2 PENDING, 1 CONFIRMED, 2 CANCELLED)");
    }

    private CreateOrderResult seed(String key, String customerId, LineItem... items) {
        return orderService.create(new CreateOrderCommand(customerId, "INR", List.of(items)), key);
    }
}
