package generics;

public class Main {
    public static void main(String[] args) {
        // Printer<Cat> printer = new Printer<>(new Cat());
        // Printer<Dog> printer = new Printer<>(new Dog());
        print(new Cat());
    }

    private static <T> void print(T animal) {
        animal.print();
    }
}