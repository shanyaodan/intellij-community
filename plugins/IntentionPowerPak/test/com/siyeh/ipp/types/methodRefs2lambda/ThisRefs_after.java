class MyTest {
    static interface SAM {
       void m(Integer i);
    }

    void m(Integer i) {}
    void m(Double d) {}

    SAM s = (i) -> m(i);
}
