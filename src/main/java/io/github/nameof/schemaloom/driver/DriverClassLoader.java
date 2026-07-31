package io.github.nameof.schemaloom.driver;

import java.net.*;
import java.util.*;

final class DriverClassLoader extends URLClassLoader {
    private final List<String> childPackages;

    DriverClassLoader(URL[] urls, ClassLoader parent, List<String> p) {
        super(urls, parent);
        childPackages = p;
    }

    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String p : childPackages)
            if (name.equals(p) || name.startsWith(p + ".")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) try {
                        c = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                    }
                    if (c != null) {
                        if (resolve) resolveClass(c);
                        return c;
                    }
                }
            }
        return super.loadClass(name, resolve);
    }
}
