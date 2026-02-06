public class RateLimiter {

/*There is one shared bucket, Token consumption is global
All requests go through it
This limits overall system traffic*/

    // --singleton Implementation (thread-safe and Lazy Loading)

    /*I make the constructor private to prevent external instantiation. 
    This guarantees that there is exactly one instance of the rate limiter in the application, which is critical because rate limiting must be globally consistent.*/

    private RateLimiter() {} //If multiple instances existed, each would have its own token bucket. That would completely break rate limiting
    
    //This uses the Initialization-on-Demand Holder idiom. 
    //The inner class is not loaded until it is referenced, which gives us lazy initialization without explicit synchronization.
    //This avoids double-checked locking and is one of the safest ways to implement a singleton in Java.
    //Thread-safe without locks. No performance penalty
    private static class Holder{
        private static final RateLimiter INSTANCE = new RateLimiter();
    }

//This provides global access to the singleton instance. The first call triggers class loading of the Holder class, creating the instance lazily and safely.
//Clean API, Lazy creation.
    public static RateLimiter getInstance() { return Holder.INSTANCE; }

 // Global token bucket: capacity = 10, refill = 1 token/sec

 //This defines a single, shared token bucket that enforces a global rate limit. 
 //The bucket allows short bursts up to 10 requests and refills at a steady rate of one request per second.
 //final - Ensures the bucket reference never changes. Prevents accidental reassignments
 //This is the system-wide throttle. Every request must pass through it
    private final TokenBucket globalBucket = new TokenBucket (10, 1);

    //evolution1: with path
    //ConcurrentHashMap ensures thread safety, when multiple users hit different path at the same time.

    //evolution2: userID + path 
    //Map -> composite key ("userId:path") to Bucket

    //one Map to rule them all, stores the Tenant Buckets and User Buckets
    private final Map<String,TokenBucket> buckets = new ConcurrentHashMap<>();

    public boolean isRequestAllowed(){
        return globalBucket.grantAccess();
    }

    //evolution1: with path
    public boolean isRequestAllowed(String path){

        //1. Identify the bucket - using the "path" argument as the key
        //computeIfAbsent will check if there is a bucket created for that specific path, if not then it will create new one, with cap 10, 
        //if yes then it returns the existing one
        TokenBucket pathBucket = buckets.computeIfAbsent(path, k -> new TokenBucket(10,1));
        //2. check the limit, will consume the tokens from the specific bucket only of that path
        //Hitting "/login" will not consume tokens from "/home"
        return pathBucket.grantAcess();
    }

    //evolution2: with userID
    public boolean isRequestAllowed(int userId, String path){
        //1. Generate the composite Key
        //ensures that user1's use of /login is tracked separately from user2's use of /login
        //also make sure that user1's use of /login is tracked separately from user1's use of /home
        String key = String.valueOf(userId) + ":" + path;

        //2. Determine capacity based on the user tier, we cannot hardcode "10" anymore, we need to ask what plan the user is on
        int capacity = getUserCapacity(userId);

        //3. Get or create the bucket.
        //The "capacity" here is only used if we are creating a new bucket, if bucket already exists then computeIfAbsent will return existing one immediately
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, 1));
        return bucket.grantAccess();
    }

    //evolution3: with userId + path
    public boolean isRequestAllowed(int userId, String path){
        String key = String.valueOf(userId) + ":" + path;
        // 1. Get User's Tier Limit (Business Logic)
        int userTierLimit = getUserCapacity(userId);
        // 2. Get Path's Hard Limit (Infrastructure/Security Logic)
        int pathHardLimit = getPathLimit(path);
        int effectiveCapacity = Math.Min(userTierLimit, pathHardLimit);
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(effectiveCapacity, 1));
        bucket.grantAccess();
    }

    //evolution4: TenantId check 

    public boolean isRequestAllowed(int userId, String path, String tenantId){
        //Check 1: Global Tenant Limit (Big bucket)
        // Key Strategy: Just the Tenant ID. Shared by ALL users of this tenant.
        String tenantKey = "TENANT:" + tenantId;
        // Dynamic Capacity: Look up plan (e.g., TenantA=10k, TenantB=100)
        int tenantCapacity = getTenantCapacity(tenantId);
        // Compute: If TenantA hits this 10,000 times, the bucket empties.
        // Note: All TenantA employees share this SAME bucket object.
        TokenBucket tenantBucket = buckets.computeIfAbsent(tenantKey, k -> new TokenBucket(tenantCapacity, 1));
        // Critical: If the company is out of tokens, BLOCK immediately. 
        // Don't bother checking the user.
        if(!tenantBucket.grantAccess()){
            System.out.println("Blocked:Tenant" + tenantId + "exceeded global quota");
            return false ;
        } 

        //check 2: User Limit (Small bucket)
        // Key Strategy: Tenant + User + Path
        // We scope the user to the tenant so 'Alice' at Acme is different from 'Alice' at Startup.
        String userKey = "TENANT:" + tenantId + ":USER:" + String.valueOf(userId) + ":PATH:" + path;
        int userTierLimit = getUserCapacity(userId);
        int pathHardLimit = getPathLimit(path);
        int effectiveCapacity = Math.Min(userTierLimit, pathHardLimit);
        TokenBucket userBucket = buckets.computeIfAbsent(userKey, k -> new TokenBucket(effectiveCapacity, 1));
        if(!userBucket.grantAccess()){
            System.out.println("Blocked:User" + userId + "is spamming");
            return false;
        }
        // Both passed!
        return true;
    }

}


public static void main(String[] args){
    RateLimiter limiter = RateLimiter.getInstance();
    for(int i=1; i <= 12; i++){
        boolean allowed = limiter.isRequestAllowed();
        if(allowed){
            System.out.println("Request" + i + ": Allowed");
        }
        else {
            System.out.println("Request" + i + ": Blocked");
        }
    }
}

// --- Helpers ---
private int getUserCapacity(int userId){
    private final Set<Integer> premiumUsers = Set.of(101, 205, 999); //In practice, user tier comes from a database or cache; for simplicity, I simulate it using a lookup set.
    DEFAULT_CAPACITY = 10;
    if(userId<=0) return DEFAULT_CAPACITY;
    if(premiumUsers.contains(userId)){
        return 100;
    }
    return 10; //free tier
}

// --- Helper for Path Limits ---
private int getPathLimit(String path) {
    if (path.equals("/login")) return 5;      // Strict security limit
    if (path.equals("/home")) return 20;  // Heavy operation limit
    return 1000; // Default: effectively unlimited by path, bounded by user tier
}
// --- Helpers ---
private int getTenantCapacity(String tenantId) {
        if (tenantId.equals("TenantA")) return 10000;
        return 100; // Default small capacity
}


//#----------------------------------------------Question Evolution---------------------------------------------#

//evolution 1: introducing path, moving from global state(one bucket) to keyed state (many buckets)
//each path has its specific tokens, so we use a HashMap, where the key is the path String and the Value is TokenBucket of the specific path
// "/login" -> 5 tokens, "/home" -> 10 tokens
private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
public boolean isRequestAllowed(String Path){
    TokenBucket pathBucket = buckets.computeIfAbsent(path, k -> new TokenBucket(10,1));
    return pathBucket.grantAccess();
}

//evolution 2: path + user
//Scenario: user1: is premium, user2 is free tier. user1_premium -> hits "/login" -> 100 tokens, 1 token utilized, 99 tokens remaining
//user2_free -> hits "/home" -> 10 tokens, 1 token utilized, 9 tokens remaining. 
//user1_premium -> hits "/home" (Different key from the "/login" bucket) -> create new bucket (100 tokens), consume 1 -> 99 tokens remaining

//make use of composite key : concatenate userID + path
//create a helper function for getUserCapacity -> this simulates looking up if the user is of free or premium tier (which tier basically)
//pass this dynamic capacity to the bucket constructor
private final Map <String, TokenBucket> buckets = new ConcurrentHashMap<>();
public boolean isRequestAllowed(int userId, String path){
    String key = String.valueOf(userId) + ":" + path;
    int capacity = getUserCapacity(userId);
    TokenBucket bucket = buckets.computeifAbsent(key, k -> new TokenBucket(capacity, 1));
    return bucket.grantAccess();
}

//evolution 3: user1(premium, 100); user2(free, 10)
//scenario1 -> hitting /home (path limit: 1000) -> user1: Math.min(100, 1000) = 100 (full premium speed), user2: Math.min(10, 1000) = 10 (limited by cheap plan)
//scenario2 -> hitting /login (path limit: 5) -> user1: Math.min(100, 5) = 5 (capped at 5 because /login is sensitive), user2: Math.min(10, 5) = 5 (capped at 5)
//Path's Limit (Math.min) ensures:
//Security: No one, not even premium users, can spam sensitive endpoints like /login. Prevents a compromised premium account from brute forcing passwords
//Fairness: Free users are still capped by their low tier limit on general pages like /home.
int userTierLimit = getUserCapacity(userId);
int pathHardLimit = getPathLimit(path);
int effectiveCapacity = Math.min(userTierLimit, pathHardLimit);

//evolution 4: TenantId (two layers of defense)
//TenantA pays for 1000 requests/sec, TenantB pays for 100 requests/sec (1. Tenant should never exceed their global quota, 2. A single User within that tenant never exceeds their personal quota)
//prevent user from using the whole company's limit
//We can't rely on just one bucket anymore. check big bucket (tenant), a small bucket(user). If either is empty, then the request is blocked

//Wait, if the Tenant check passes (consumes 1 token), but the User check fails... haven't we wrongly deducted a token from the Tenant?"
//In high-scale systems (like Cloudflare), this is usually acceptable. A blocked request still costs us infrastructure money to process, so charging the tenant quota is fair.
//Complex Answer: If strict correctness is required, we would need a 'rollback' method (tenantBucket.addToken()), or check both without consuming first. But for a rate limiter, 'Fail Closed' is usually safer."


//evolution 5: Time-independent: process historical logs or distributed streams.
//Stop using System.currentTimeMillis(). 
//We process logs that might be hours old. Use the timestamp parameter passed in the request to decide if the request was allowed at that moment.
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {

    // Singleton Logic
    private RateLimiter() {}
    private static class Holder { private static final RateLimiter INSTANCE = new RateLimiter(); }
    public static RateLimiter getInstance() { return Holder.INSTANCE; }

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // FINAL SIGNATURE with all parameters
    public boolean isAllowed(String ip, String userId, String path, String method, String tenantId, String timestampStr) {
        
        // 1. Parse Timestamp (Assume standard epoch millis string)
        long requestTime = Long.parseLong(timestampStr);

        // ---------------------------------------------------------
        // CHECK 1: Tenant Limit (Using Log Time)
        // ---------------------------------------------------------
        String tenantKey = "TENANT:" + tenantId;
        int tenantCap = getTenantCapacity(tenantId);
        
        // Pass 'requestTime' to constructor!
        TokenBucket tenantBucket = buckets.computeIfAbsent(tenantKey, 
            k -> new TokenBucket(tenantCap, 1, requestTime));

        if (!tenantBucket.tryConsume(requestTime)) {
            return false;
        }

        // ---------------------------------------------------------
        // CHECK 2: User Limit (Using Log Time)
        // ---------------------------------------------------------
        String userKey = "TENANT:" + tenantId + ":USER:" + userId + ":PATH:" + path;
        int userTierLimit = getUserCapacity(userId);
        int pathHardLimit = getPathLimit(path);
        int effectiveCapacity = Math.min(userTierLimit, pathHardLimit);
        
        TokenBucket userBucket = buckets.computeIfAbsent(userKey, 
            k -> new TokenBucket(effectiveCapacity, 1, requestTime));

        return userBucket.tryConsume(requestTime);
    }

    // --- Config Helpers ---
    private int getTenantCapacity(String id) { return id.equals("ACME") ? 10000 : 100; }
    private int getUserCapacity(String id) { return 10; }
}

//All Scenarios:
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {

    // --- Singleton Logic ---
    private RateLimiter() {}
    private static class Holder { 
        private static final RateLimiter INSTANCE = new RateLimiter(); 
    }
    public static RateLimiter getInstance() { return Holder.INSTANCE; }

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // FINAL SIGNATURE: Includes IP and Method
    public boolean isAllowed(String ip, String userId, String path, String method, String tenantId, String timestampStr) {
        
        // 1. Setup Request Context
        long requestTime = Long.parseLong(timestampStr);
        
        // LOGIC: Weighted Costs
        // POST/PUT/DELETE are expensive (writes). GET is cheap (reads).
        int cost = (method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT")) ? 5 : 1;

        // ---------------------------------------------------------
        // CHECK 1: Tenant Limit (Global)
        // ---------------------------------------------------------
        String tenantKey = "TENANT:" + tenantId;
        int tenantCap = getTenantCapacity(tenantId);
        
        TokenBucket tenantBucket = buckets.computeIfAbsent(tenantKey, 
            k -> new TokenBucket(tenantCap, tenantCap, requestTime));

        // NOTE: We pass 'cost' here. A heavy POST counts as 5 against the company quota too.
        if (!tenantBucket.tryConsume(cost, requestTime)) {
            System.out.println("Blocked: Tenant " + tenantId + " exceeded quota.");
            return false;
        }

        // ---------------------------------------------------------
        // CHECK 2: User / IP Limit (Specific)
        // ---------------------------------------------------------
        
        // LOGIC: Identity Fallback
        // If userId is null, we treat them as an "Anonymous User" identified by IP.
        String identityKey;
        int baseCapacity;

        if (userId != null && !userId.isEmpty()) {
            identityKey = "USER:" + userId;
            baseCapacity = getUserCapacity(userId); // e.g. Gold=100
        } else {
            identityKey = "IP:" + ip;
            baseCapacity = 10; // Anonymous users get strict limits
        }

        // LOGIC: Path Security
        // Even Gold users can't spam /login
        int pathLimit = getPathLimit(path);
        
        // INTERSECTION: Take the stricter of the two limits
        int effectiveCapacity = Math.min(baseCapacity, pathLimit);

        // COMPOSITE KEY: Tenant + Identity + Path
        // Examples: 
        // "TENANT:ACME:USER:Alice:PATH:/home"
        // "TENANT:ACME:IP:1.2.3.4:PATH:/login"
        String specificKey = "TENANT:" + tenantId + ":" + identityKey + ":PATH:" + path;

        TokenBucket userBucket = buckets.computeIfAbsent(specificKey, 
            k -> new TokenBucket(effectiveCapacity, 1, requestTime));

        // Consume tokens from the specific bucket
        if (!userBucket.tryConsume(cost, requestTime)) {
             System.out.println("Blocked: " + identityKey + " exceeded limit on " + path);
             return false;
        }

        return true;
    }

    // --- Helpers ---
    private int getTenantCapacity(String id) { return id.equals("ACME") ? 10000 : 100; }
    
    // Updated to accept String (User ID logic)
    private int getUserCapacity(String userId) { 
        if (userId.startsWith("PREMIUM")) return 100; 
        return 10; 
    }
    
    private int getPathLimit(String path) {
        if (path.equals("/login")) return 5;
        return 1000;
    }
}

/*
Scenario A: The Hacker (Anonymous IP spamming Login)
Input: ip="99.99.99.99", userId=null, path="/login", method="POST", tenant="ACME".
Step 1 (Cost): Method is POST, so Cost = 5.
Step 2 (Tenant): ACME has 10,000 tokens. 10000 - 5 = 9995. Allowed.
Step 3 (Identity): userId is null.
identityKey = "IP:99.99.99.99".
baseCapacity = 10.
Step 4 (Path): Path is /login. pathLimit = 5.
Step 5 (Intersection): Math.min(10, 5) = Capacity 5.
Step 6 (Consume): Bucket created with 5 tokens. We try to consume Cost (5).
5 >= 5 -> True. Remaining: 0.
Result: Allowed, but bucket is now empty.
Next Request: If the hacker tries again immediately, 0 >= 5 fails. Blocked.

Scenario B: The Premium User (Alice browsing Home)
Input: ip="1.2.3.4", userId="PREMIUM_ALICE", path="/home", method="GET", tenant="ACME".
Step 1 (Cost): Method is GET, so Cost = 1.
Step 2 (Identity): userId exists.
identityKey = "USER:PREMIUM_ALICE".
baseCapacity = 100.
Step 3 (Path): Path is /home. pathLimit = 1000.
Step 4 (Intersection): Math.min(100, 1000) = Capacity 100.
Step 5 (Consume): Bucket has 100. Consume 1. Remaining: 99.
Result: Allowed.
*/
