// !LANGUAGE: +MultiPlatformProjects
// MODULE: m1-common
// FILE: common.kt

header annotation class Foo1
header annotation class Foo2
header annotation class Foo3
header annotation class Foo4

// MODULE: m2-jvm(m1-common)

// FILE: Bar1.java

public @interface Bar1 {
    String value() default "";
}

// FILE: Bar2.java

public @interface Bar2 {
    String value() default "";
    String path();
}

// FILE: jvm.kt

impl typealias Foo1 = Bar1

<!HEADER_CLASS_MEMBERS_ARE_NOT_IMPLEMENTED!>impl typealias Foo4 = Bar2<!>

impl annotation class Foo2(val p: String = "default")

impl annotation class Foo3(val a: String = "a", val b: String = "b")