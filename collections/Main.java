import java.util.Queue;
import java.util.LinkedList;

public class Main {

    public static void main(String[] args) {

        // Create a queue
        Queue<String> queue = new LinkedList<>();

        // Add elements (enqueue)
        queue.add("Alice");
        queue.add("Bob");
        queue.add("Charlie");
        queue.add("David");

        System.out.println("Queue: " + queue);

        // Look at the first element without removing it
        System.out.println("Front: " + queue.peek());

        // Remove elements (dequeue)
        System.out.println("Removed: " + queue.poll());
        System.out.println("Removed: " + queue.poll());

        // Display remaining queue
        System.out.println("Queue after removals: " + queue);

        // Check the next element
        System.out.println("Next in line: " + queue.peek());

        // Check queue size
        System.out.println("Queue size: " + queue.size());

        // Check if queue is empty
        System.out.println("Is queue empty? " + queue.isEmpty());
    }
}
