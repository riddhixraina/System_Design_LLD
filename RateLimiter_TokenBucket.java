//Token Bucket - capacity of bucket (maxRequests), refillRate(tokens added per second), token(current balance), lastRefillTimeStamp(when we last refilled)

public class TokenBucket{
    private final int capacity; //max tokens the bucket can hold
    private final int refillRatePerSecond; //tokens added per second
    private double tokens; //current balance
    private long lastRefillTimeStamp; //last time we refilled

    public TokenBucket(int capacity, int refillRatePerSecond){
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.tokens = capacity; //start at the full capacity, allows initial burst
        this.lastRefillTimeStamp = System.currentTimeMillis();
    }

    //Making the function synchronized to make it thread safe
    public synchronized boolean grantAccess(){
        /*
        Refilling before checking is like updating your bank balance before approving a withdrawal. 
        If you don’t account for recent deposits, you might incorrectly deny a valid transaction.
        We call refill() first so that the token count reflects all the time that has passed since the last request.
        Otherwise, we would be making decisions using stale token data and incorrectly rejecting valid requests.
        What would go wrong if we DIDN’T refill first?

        Let’s do a dry run without refill.
        Configuration
        capacity = 10
        refillRate = 2 tokens/sec
        Time = 0 sec
        tokens = 0
        Wait 5 seconds (NO requests during this time)
        Correct behavior:
        tokens should now be 10 (capped)
        Request arrives at time = 5 sec
        ❌ If we skip refill():
        if (tokens >= 1) // tokens still 0
        ➡️ Request is incorrectly rejected. Even though the user waited long enough. That’s a bug.

        Now the same scenario WITH refill()
        Time = 5 sec, request arrives
        refill()

        Calculation:
        secondsPassed = 5
        tokensToAdd = 5 * 2 = 10
        tokens = min(10, 0 + 10) = 10

        Then:
        tokens >= 1 → true
        tokens-- → 9
        ✅ Request allowed (correct behavior)
        */

        refill(); 
        if(tokens >= 1){
            tokens-=1;
            return true; //Allowed
        }
        return false; //Blocked

    }

    private void refill(){
        long currentTime = System.currentTimeMillis();

        double secondsPassed = (currentTime - lastRefillTimeStamp) * 1000.0; //calculate elapsed time = How many seconds since last refill?
        double tokensToAdd = secondsPassed * refillRatePerSecond; //2 secs passed, refill rate is 5tokens/sec, add 10 tokens

        tokens = Math.min(capacity, tokens + tokensToAdd); //Bucket can't overflow
        lastRefillTimeStamp = currentTime; //Next refill calculation starts from here
    }
     
}

public class TokenBucket {
    private final int capacity;
    private final int refillRate;
    
    private double tokens;
    private long lastRefillTimestamp;

    // CHANGE 1: Constructor takes 'creationTime'
    // If we create a bucket for a log entry from 10:00 AM, 
    // the bucket must start at 10:00 AM, not "Now".
    public TokenBucket(int capacity, int refillRate, long creationTime) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTimestamp = creationTime;
    }

    // CHANGE 2: tryConsume takes 'now' (The Event Time)
    public synchronized boolean tryConsume(long now) {
        refill(now);
        
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    // It must accept 'cost' as an argument
public synchronized boolean tryConsume(int cost, long now) {
    refill(now);
    if (tokens >= cost) { // Check against COST, not 1
        tokens -= cost;
        return true;
    }
    return false;
}
    private void refill(long now) {
        // CRITICAL CHECK: Time Travel Safety
        // If logs arrive out of order (e.g., Log A at 10:01 arrives AFTER Log B at 10:02),
        // we must NOT refill backwards.
        if (now <= lastRefillTimestamp) {
            return;
        }

        double secondsPassed = (now - lastRefillTimestamp) / 1000.0;
        double tokensToAdd = secondsPassed * refillRate;
        
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillTimestamp = now;
    }
}

