public class DataHolder {
    private int data;

    public synchronized int getData() {
        return data;
    }

    public synchronized void increment() {
        data++;
    }
}
