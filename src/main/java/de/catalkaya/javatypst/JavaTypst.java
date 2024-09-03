package de.catalkaya.javatypst;

import io.questdb.jar.jni.JarJniLoader;

public class JavaTypst {
    static {
        JarJniLoader.loadLib(JavaTypst.class, "/de/catalkaya/javatypst/libs", "typst_string");
    }

    public static native byte[] render(String content);
}
