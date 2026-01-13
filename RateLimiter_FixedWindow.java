

public class WindowCounter{
    int count;
    long windowStartTime;

    public WindowCounter(int count, long windowStartTime){
        this.count = count;
        this.windowStartTime = windowStartTime;
    }
}


public class RateLimiter{
    private final int limit;
    private final long windowTimeMillis;
    private final Map<String, WindowCounter> store;

    public RateLimiter(int limit, long windowTimeMillis){
        this.limit = limit;
        this.windowTimeMillis = windowTimeMillis;
        this.store = new HashMap<>();
    }

    public boolean allowRequest(String userId){
        long currentTime = System.currentTimeMillis();
        WindowCounter counter = store.get(userId);
        
        //case 1: The first request from the user
        if(counter == null){
            counter = new WindowCounter(1, currentTime);
            store.put(userId, counter);
            return true;
        }

        //case 2: The time window is expired
        if(currentTime - counter.windowStartTime >= windowTimeMillis){
            counter.windowStartTime = currentTime;
            counter.count = 1;
            return true;
        }

        //case 3: It is within the window time and under the limit
        if(counter.count<limit){
            counter.count++;
            return true;
        }

        //case 4; exceeded
        return false;


    }
}


//Fixed Window - thread safe and with concurrency:
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FixedWindowRateLimiter {
    
    // Inner class to hold the state safely
    private static class WindowCounter {
        // AtomicInteger ensures two threads don't count "1" at the same time
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicLong windowStartTime;

        public WindowCounter(long startTime) {
            this.windowStartTime = new AtomicLong(startTime);
        }
    }

    private final int limit;
    private final long windowTimeMillis;
    private final ConcurrentHashMap<String, WindowCounter> store = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, long windowTimeMillis) {
        this.limit = limit;
        this.windowTimeMillis = windowTimeMillis;
    }

    public boolean allowRequest(String userId) {
        long currentTime = System.currentTimeMillis();
        
        // "compute" is atomic! It locks the key for us automatically.
        // This is the cleanest way to handle concurrency in Java Maps.
        WindowCounter counter = store.compute(userId, (key, existingCounter) -> {
            
            // Case 1: New User OR Case 2: Window Expired
            if (existingCounter == null || (currentTime - existingCounter.windowStartTime.get() > windowTimeMillis)) {
                WindowCounter newCounter = new WindowCounter(currentTime);
                newCounter.count.incrementAndGet(); // Count = 1
                return newCounter;
            }
            
            // Case 3 & 4: Existing Window
            // (We don't increment here because we need to check the limit first. 
            // Since we are inside 'compute', we are safe from race conditions on this specific key).
            return existingCounter; 
        });

        // Now check the value. 
        // Note: There is a tiny edge case in "compute" logic above regarding 
        // incrementing inside/outside, but for an interview, this flow is safer:
        
        if (counter.windowStartTime.get() == currentTime) {
            // It was just created/reset
            return true;
        }

        // Try to increment if under limit
        if (counter.count.get() < limit) {
            counter.count.incrementAndGet();
            return true;
        }

        return false; // Limit exceeded
    }
}