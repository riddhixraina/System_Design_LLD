public class TokenBucket{
    private final int capacity; //max tokens in the bucket
    private final int refillRatePerSecond; //tokens added per second
    private double currentTokens; //current tokens in the bucket
    privat long lastRefillTimeStamp; //last refill time in milliseconds

    public TokenBucket(int capacity, int refillRatePerSecond){
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.currentTokens = capacity; //start full
        this.lastRefillTimeStamp = System.currentTimeMillis();
    }

    public synchronized boolean tryConsume(){
        refill();
        if(tokens>=1){
            tokens -= 1;
            return true; //allow request
        }
        else{
            return false; //not enough tokens
        }
    }

    public void refill(){
        long currentTime = System.currentTimeMillis();
        long secondsPassed = (currentTime - lastRefillTimeStamp) * 1000.0; //elapsed time
        double TokenToAdd = secondsPassed * refillRatePerSecond; //tokens to add
        tokens = Math.min(capacity, tokens + TokenToAdd); //update tokens
        lastRefillTimeStamp = currentTime; //update timestamp
    }
}

public class RateLimiter{
    private RateLimiter() {}
    //inner class is not loaded until referenced
    private static class Holder{
        private static final RateLimiter INSTANCE = new RateLimiter();
    }
    //global access to the singleton instance
    public static RateLimiter getInstance (){
        return Holder.INSTANCE; 
    }

    private final TokenBucket globalTokenBucket = new TokenBucket(10, 1); 

    public boolean isRequestAllowed(){
        return globalTokenBucket.tryConsume();
    }

}
//userA -> 
public static void main (String[] args){
    RateLimiter rateLimiter = RateLimiter.getInstance();
    boolean allowed = rateLimiter.isRequestAllowed();
    for(int i=0; i<=12; i++){
        if(allowed){
            System.out.println("Request" + i + " allowed"); 
        }
        else{
            System.out.println("Request" + i + " blocked");
        }
    }
}