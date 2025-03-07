# Real-Time Stock Trading Engine

## Overview
This repository contains a Java implementation of a real-time stock trading engine that efficiently matches buy and sell orders for stocks. The system supports up to 1,024 different ticker symbols and provides thread-safe, lock-free operations for high concurrency.

## Features
- **Order Management**: Add buy and sell orders with specified ticker symbol, quantity, and price
- **Efficient Matching Algorithm**: O(n) time complexity for matching orders
- **Lock-Free Implementation**: Uses atomic operations to handle race conditions
- **Concurrent Processing**: Supports multiple threads modifying the order book simultaneously
- **Simulation Capability**: Includes a wrapper to simulate active stock trading

## Implementation Details

### Key Components
1. **Order**: Represents a stock order with type (buy/sell), ticker ID, quantity, price, and matching status
2. **OrderList**: Lock-free linked list implementation using AtomicReference for concurrent access
3. **OrderBook**: Maintains separate buy and sell order lists for each ticker symbol
4. **StockTradingEngine**: Provides public API methods and simulation functionality

### Core Functions
1. **addOrder(String type, String ticker, int quantity, double price)**
    - Takes order type (buy/sell), ticker symbol, quantity, and price
    - Converts ticker symbol to numeric ID
    - Creates and adds the order to appropriate list
    - Returns unique order ID

2. **matchOrders()**
    - Matches buy and sell orders for all tickers
    - Uses O(n) algorithm with counting sort and linear scan
    - Executes trades when buy price ≥ sell price
    - Updates order book to reflect completed trades

### Time Complexity
- **Order Addition**: O(1)
- **Order Matching**: O(n) where n is the total number of orders in the book
- **Trade Execution**: O(1) per matched pair

### Space Complexity
- O(m) where m is the maximum number of orders (100,000 in this implementation)

## Algorithm Design
The matching algorithm achieves O(n) time complexity through:
1. Collecting all orders for a ticker in arrays (O(n))
2. Sorting using counting sort (O(n) with bounded price range)
3. Linear scan to match orders (O(n))
4. Cleanup and rebuilding the order book (O(n))

## Thread Safety
The implementation uses lock-free data structures and atomic operations:
- AtomicReference for linked list nodes
- CompareAndSet operations for thread-safe modifications
- Lock-free order ID generation with AtomicInteger

## How to Run
1. Clone the repository
2. Compile the Java source file:
   ```
   javac StockTradingEngine.java
   ```
3. Run the program:
   ```
   java StockTradingEngine
   ```

## Sample Output
```
Starting stock trading simulation...
Added BUY order for ticker 123: Order{id=1, type=BUY, tickerId=123, quantity=500, price=120.5, isMatched=false}
Added SELL order for ticker 123: Order{id=2, type=SELL, tickerId=123, quantity=200, price=119.5, isMatched=false}
Matched: BuyOrder=1 with SellOrder=2 for ticker=123 quantity=200 at price=119.5
...
Trading simulation completed.
```

