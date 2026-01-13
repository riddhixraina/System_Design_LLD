import java.util.*;

/*
using an interface here because the grantAccess() method will be used in every different algorithm approach that we write for calling the RateLimiter.
*/

public interface RateLimiter{
    public boolean grantAccess();
} 


public class SlidingWindow implements RateLimiter{
    // Remove entries that are before time
    Queue<Integer> slidingWindow;
    int capacity;
    int time; //time to live

//Constructor and Initialization
public SlidingWindow(int capacity, int time){
    this.capacity = capacity;
    this.time = time;
    slidingWindow = new ConcurrentLinkedQueue<>();
}

@Override
public boolean grantAccess(){
    //updateQueue based on current time so that only valid requests is there
    long currentTime = System.currentTimeMillis();
    updateQueue(currentTime);

    if(slidingWindow.size() < capacity){
        slidingWindow.offer((int) currentTime);
        return true;
    }
    return false;
}

private void updateQueue(long currentTime){
    if(slidingWindow.Empty()) return;
    long time = (currentTime - slidingWindow.peek())/1000;

    while (time >= this.time){
        slidingWindow.poll();
        if(slidingWindow.Empty()) break; //when time is empty
        time = (currentTime - slidingWindow.peek())/1000;
    }
}
}


public class UserSlidingWindow{
    Map<Integer, SlidingWindow> bucket;

    //constructor
    public UserSlidingWindow(int id){
        bucket = new HashMap<>();
        bucket.put(id, new SlidingWindow(1, 10));
    }

    void accessApplication(int id){
        if(bucket.get(id).grantAccess()) {
            System.out.println("Access Allowed");
        }
        else{
            System.out.println("Access Blocked");
        }
    }
}