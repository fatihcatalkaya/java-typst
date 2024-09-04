package io.github.fatihcatalkaya.javatypst;

import io.questdb.jar.jni.JarJniLoader;

public class JavaTypst {
    static {
        JarJniLoader.loadLib(JavaTypst.class, "/io/github/fatihcatalkaya/javatypst/libs", "java_typst");
    }

    public static native byte[] render(String content);
}
