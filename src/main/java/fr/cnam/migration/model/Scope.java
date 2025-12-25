package fr.cnam.migration.model;

/**
 * Maven dependency scope.
 */
public enum Scope {
    COMPILE("compile"),
    PROVIDED("provided"),
    RUNTIME("runtime"),
    TEST("test"),
    SYSTEM("system");

    private final String value;

    Scope(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
