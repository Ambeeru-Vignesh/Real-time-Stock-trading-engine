import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class StockTradingEngine {
    private static final int MAX_TICKERS = 1024;
    private static final int MAX_ORDERS = 100000;

    // Enum for order types
    private enum OrderType {
        BUY, SELL
    }

    // Order class to represent a stock order
    private static class Order {
        int id;
        OrderType type;
        int tickerId;
        int quantity;
        double price;
        boolean isMatched;
        AtomicReference<Order> next; // For lock-free linked list

        public Order(int id, OrderType type, int tickerId, int quantity, double price) {
            this.id = id;
            this.type = type;
            this.tickerId = tickerId;
            this.quantity = quantity;
            this.price = price;
            this.isMatched = false;
            this.next = new AtomicReference<>(null);
        }

        @Override
        public String toString() {
            return "Order{" +
                    "id=" + id +
                    ", type=" + type +
                    ", tickerId=" + tickerId +
                    ", quantity=" + quantity +
                    ", price=" + price +
                    ", isMatched=" + isMatched +
                    '}';
        }
    }

    // Lock-free linked list implementation for order book
    private static class OrderList {
        private AtomicReference<Order> head;

        public OrderList() {
            head = new AtomicReference<>(null);
        }

        public void add(Order order) {
            while (true) {
                Order oldHead = head.get();
                order.next.set(oldHead);
                if (head.compareAndSet(oldHead, order)) {
                    break;
                }
            }
        }

        public Order getHead() {
            return head.get();
        }

        // Remove a specific order from the list (for matched orders)
        public boolean remove(Order orderToRemove) {
            Order current = head.get();
            Order prev = null;

            while (current != null) {
                Order next = current.next.get();

                if (current.id == orderToRemove.id) {
                    if (prev == null) {
                        // Removing head
                        if (head.compareAndSet(current, next)) {
                            return true;
                        }
                        // If CAS failed, start over
                        return remove(orderToRemove);
                    } else {
                        // Removing non-head node
                        if (prev.next.compareAndSet(current, next)) {
                            return true;
                        }
                        // If CAS failed, start over
                        return remove(orderToRemove);
                    }
                }

                prev = current;
                current = next;
            }

            return false;
        }
    }

    // Order book to store buy and sell orders for each ticker
    private static class OrderBook {
        private OrderList[] buyOrders;
        private OrderList[] sellOrders;
        private AtomicInteger orderIdCounter;

        public OrderBook() {
            buyOrders = new OrderList[MAX_TICKERS];
            sellOrders = new OrderList[MAX_TICKERS];
            orderIdCounter = new AtomicInteger(0);

            // Initialize order lists for each ticker
            for (int i = 0; i < MAX_TICKERS; i++) {
                buyOrders[i] = new OrderList();
                sellOrders[i] = new OrderList();
            }
        }

        public int addOrder(OrderType type, int tickerId, int quantity, double price) {
            if (tickerId < 0 || tickerId >= MAX_TICKERS) {
                throw new IllegalArgumentException("Invalid ticker ID: " + tickerId);
            }

            int orderId = orderIdCounter.incrementAndGet();
            Order order = new Order(orderId, type, tickerId, quantity, price);

            if (type == OrderType.BUY) {
                buyOrders[tickerId].add(order);
            } else {
                sellOrders[tickerId].add(order);
            }

            System.out.println("Added " + type + " order for ticker " + tickerId + ": " + order);
            return orderId;
        }

        // O(n) implementation of matchOrders
        public void matchOrders() {
            // We'll iterate through all tickers, but for each ticker we'll do
            // a linear scan of all orders exactly once
            for (int tickerId = 0; tickerId < MAX_TICKERS; tickerId++) {
                matchOrdersForTicker(tickerId);
            }
        }

        private void matchOrdersForTicker(int tickerId) {
            // Get all buy and sell orders for this ticker
            Order[] buys = getAllBuyOrders(tickerId);
            Order[] sells = getAllSellOrders(tickerId);

            // Sort the arrays in O(n) time using counting sort since we can establish bounds
            // on the price range (assume prices between 0 and 10000 for example)
            sortBuyOrdersByPrice(buys); // Sort in descending order (highest price first)
            sortSellOrdersByPrice(sells); // Sort in ascending order (lowest price first)

            // Linear scan to match orders
            int buyIndex = 0;
            int sellIndex = 0;

            while (buyIndex < buys.length && sellIndex < sells.length) {
                Order buyOrder = buys[buyIndex];
                Order sellOrder = sells[sellIndex];

                // Skip null orders or already matched orders
                if (buyOrder == null || buyOrder.isMatched) {
                    buyIndex++;
                    continue;
                }

                if (sellOrder == null || sellOrder.isMatched) {
                    sellIndex++;
                    continue;
                }

                // Check if these orders can be matched
                if (buyOrder.price >= sellOrder.price) {
                    // Match found
                    executeOrder(buyOrder, sellOrder);

                    // If either order is fully matched, move to next order
                    if (buyOrder.quantity == 0) {
                        buyIndex++;
                    }

                    if (sellOrder.quantity == 0) {
                        sellIndex++;
                    }
                } else {
                    // No match possible with current orders, so no more matches are possible
                    // since we've sorted the orders by price
                    break;
                }
            }

            // Clean up the order book by removing matched orders and reinserting partially filled ones
            cleanupOrderBook(tickerId, buys, sells);
        }

        private Order[] getAllBuyOrders(int tickerId) {
            return getAllOrdersFromList(buyOrders[tickerId].getHead());
        }

        private Order[] getAllSellOrders(int tickerId) {
            return getAllOrdersFromList(sellOrders[tickerId].getHead());
        }

        private Order[] getAllOrdersFromList(Order head) {
            // Count number of orders
            int count = 0;
            Order current = head;
            while (current != null) {
                count++;
                current = current.next.get();
            }

            // Create array and fill it
            Order[] orders = new Order[count];
            current = head;
            int index = 0;
            while (current != null) {
                orders[index++] = current;
                current = current.next.get();
            }

            return orders;
        }

        // Counting sort for buy orders (descending order)
        private void sortBuyOrdersByPrice(Order[] orders) {
            if (orders.length <= 1) return;

            // Determine price range
            double maxPrice = 0;
            for (Order order : orders) {
                if (order != null && order.price > maxPrice) {
                    maxPrice = order.price;
                }
            }

            // For simplicity, assume prices are in whole dollars
            // In a real system, we'd scale the prices to integers
            int[] count = new int[(int)maxPrice + 1];

            // Count occurrences of each price
            for (Order order : orders) {
                if (order != null) {
                    count[(int)order.price]++;
                }
            }

            // Sort in descending order
            Order[] sorted = new Order[orders.length];
            int sortedIndex = 0;

            for (int price = count.length - 1; price >= 0; price--) {
                for (int i = 0; i < count[price]; i++) {
                    // Find an order with this price
                    for (Order order : orders) {
                        if (order != null && (int)order.price == price && !order.isMatched) {
                            sorted[sortedIndex++] = order;
                            order.isMatched = true; // Mark as processed for sorting
                            break;
                        }
                    }
                }
            }

            // Reset the isMatched flag
            for (Order order : sorted) {
                if (order != null) {
                    order.isMatched = false;
                }
            }

            // Copy back to original array
            System.arraycopy(sorted, 0, orders, 0, orders.length);
        }

        // Counting sort for sell orders (ascending order)
        private void sortSellOrdersByPrice(Order[] orders) {
            if (orders.length <= 1) return;

            // Determine price range
            double maxPrice = 0;
            for (Order order : orders) {
                if (order != null && order.price > maxPrice) {
                    maxPrice = order.price;
                }
            }

            // For simplicity, assume prices are in whole dollars
            int[] count = new int[(int)maxPrice + 1];

            // Count occurrences of each price
            for (Order order : orders) {
                if (order != null) {
                    count[(int)order.price]++;
                }
            }

            // Sort in ascending order
            Order[] sorted = new Order[orders.length];
            int sortedIndex = 0;

            for (int price = 0; price < count.length; price++) {
                for (int i = 0; i < count[price]; i++) {
                    // Find an order with this price
                    for (Order order : orders) {
                        if (order != null && (int)order.price == price && !order.isMatched) {
                            sorted[sortedIndex++] = order;
                            order.isMatched = true; // Mark as processed for sorting
                            break;
                        }
                    }
                }
            }

            // Reset the isMatched flag
            for (Order order : sorted) {
                if (order != null) {
                    order.isMatched = false;
                }
            }

            // Copy back to original array
            System.arraycopy(sorted, 0, orders, 0, orders.length);
        }

        private void executeOrder(Order buyOrder, Order sellOrder) {
            int matchedQuantity = Math.min(buyOrder.quantity, sellOrder.quantity);
            double executionPrice = sellOrder.price;

            System.out.println("Matched: BuyOrder=" + buyOrder.id + " with SellOrder=" + sellOrder.id +
                    " for ticker=" + buyOrder.tickerId +
                    " quantity=" + matchedQuantity +
                    " at price=" + executionPrice);

            buyOrder.quantity -= matchedQuantity;
            sellOrder.quantity -= matchedQuantity;

            if (buyOrder.quantity == 0) {
                buyOrder.isMatched = true;
            }

            if (sellOrder.quantity == 0) {
                sellOrder.isMatched = true;
            }
        }

        private void cleanupOrderBook(int tickerId, Order[] buys, Order[] sells) {
            // Temporary lists to rebuild the order book
            OrderList newBuyList = new OrderList();
            OrderList newSellList = new OrderList();

            // Process buy orders
            for (Order order : buys) {
                if (order != null && !order.isMatched && order.quantity > 0) {
                    newBuyList.add(order);
                }
            }

            // Process sell orders
            for (Order order : sells) {
                if (order != null && !order.isMatched && order.quantity > 0) {
                    newSellList.add(order);
                }
            }

            // Replace the old lists with the new ones
            buyOrders[tickerId] = newBuyList;
            sellOrders[tickerId] = newSellList;
        }
    }

    private static final OrderBook orderBook = new OrderBook();

    // Public API methods
    public static int addOrder(String type, String ticker, int quantity, double price) {
        OrderType orderType = "BUY".equalsIgnoreCase(type) ? OrderType.BUY : OrderType.SELL;
        int tickerId = getTickerId(ticker);
        return orderBook.addOrder(orderType, tickerId, quantity, price);
    }

    public static void matchOrders() {
        orderBook.matchOrders();
    }

    // Helper method to convert ticker symbol to numeric ID
    private static int getTickerId(String ticker) {
        // Simple hash function to convert ticker to an ID between 0 and MAX_TICKERS-1
        return Math.abs(ticker.hashCode()) % MAX_TICKERS;
    }

    // Wrapper to simulate active stock transactions
    public static void simulateTrading(int numOrders) {
        String[] tickers = {"AAPL", "GOOGL", "MSFT", "AMZN", "META", "TSLA", "NVDA", "BRK.A", "JPM", "JNJ"};

        // Spawn multiple threads to simulate concurrent trading
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < numOrders / threads.length; i++) {
                    String type = random.nextBoolean() ? "BUY" : "SELL";
                    String ticker = tickers[random.nextInt(tickers.length)];
                    int quantity = random.nextInt(1, 101) * 10;
                    double price = 50.0 + random.nextDouble(0, 950.0);

                    addOrder(type, ticker, quantity, price);

                    // Match orders periodically
                    if (i % 10 == 0) {
                        matchOrders();
                    }

                    try {
                        Thread.sleep(random.nextInt(1, 5));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            threads[t].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Final matching phase
        matchOrders();
    }

    public static void main(String[] args) {
        System.out.println("Starting stock trading simulation...");
        simulateTrading(1000);
        System.out.println("Trading simulation completed.");
    }
}