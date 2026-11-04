import java.util.concurrent.Executors;

public class Multithreading {
    private static final int NUM_THREADS = 10;
    private static final int NUM_ITERATIONS = 10_000;

    public static void main(String[] args) {
        final var data = new DataHolder();

        try(final var executor = Executors.newFixedThreadPool(NUM_THREADS)) {
            for (int i = 0; i < NUM_THREADS; i++) {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        for (int j = 0; j < NUM_ITERATIONS; j++) {
                            data.increment();
                        }
                    }
                });
            }
        }

        System.out.println("Final data value: " + data.getData());
    }
}
