/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.wildfly.galleon.plugin.transformer;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.wildfly.transformer.Transformer;

/**
 *
 * @author jdenise
 */
public abstract class JakartaTransformer {

    public static final String TRANSFORM_ARTIFACTS = "jakarta.transform.artifacts";
    public static final String TRANSFORM_MODULES = "jakarta.transform.modules";

    private static void safeClose(final Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (final Throwable t) {
            // ignored
        }
    }

    private static void readBytes(final InputStream is, final byte[] clazz, final boolean closeStream) throws IOException {
        try {
            int offset = 0;
            while (offset < clazz.length) {
                offset += is.read(clazz, offset, clazz.length - offset);
            }
        } finally {
            if (closeStream) {
                safeClose(is);
            }
        }
    }

    private static void writeBytes(final OutputStream os, final byte[] clazz, final boolean closeStream) throws IOException {
        try {
            os.write(clazz);
        } finally {
            if (closeStream) {
                safeClose(os);
            }
        }
    }

    public static void transformJarFile(final File inJarFile, final File outJarFile) throws IOException {
        final org.wildfly.transformer.TransformerBuilder t = org.wildfly.transformer.TransformerFactory.getInstance().newTransformer();
        final Calendar calendar = Calendar.getInstance();
        JarFile jar = null;
        JarOutputStream jarOutputStream = null;
        JarEntry inJarEntry, outJarEntry;
        byte[] buffer;
        Transformer.Resource oldResource, newResource;
        final org.wildfly.transformer.Transformer transformer = t.build();
        try {
            jar = new JarFile(inJarFile);
            jarOutputStream = new JarOutputStream(new FileOutputStream(outJarFile));

            for (final Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements();) {
                // jar file entry preconditions
                inJarEntry = e.nextElement();
                if (inJarEntry.getSize() == 0) {
                    continue; // directories
                }
                if (inJarEntry.getSize() < 0) {
                    throw new UnsupportedOperationException("File size " + inJarEntry.getName() + " unknown! File size must be positive number");
                }
                if (inJarEntry.getSize() > Integer.MAX_VALUE) {
                    throw new UnsupportedOperationException("File " + inJarEntry.getName() + " too big! Maximum allowed file size is " + Integer.MAX_VALUE + " bytes");
                }
                // reading original jar file entry
                buffer = new byte[(int) inJarEntry.getSize()];
                readBytes(jar.getInputStream(inJarEntry), buffer, true);
                oldResource = new Transformer.Resource(inJarEntry.getName(), buffer);
                // transform resource
                newResource = transformer.transform(oldResource);
                if (newResource == null) {
                    newResource = oldResource;
                }
                // writing potentially modified jar file entry
                outJarEntry = new JarEntry(newResource.getName());
                outJarEntry.setSize(newResource.getData().length);
                outJarEntry.setTime(calendar.getTimeInMillis());
                jarOutputStream.putNextEntry(outJarEntry);
                writeBytes(jarOutputStream, newResource.getData(), false);
                jarOutputStream.closeEntry();
            }
        } finally {
            safeClose(jar);
            safeClose(jarOutputStream);
        }
    }

    public static void transformModules(Path modules) throws IOException {
        JBossModuleTransformer.transform(modules);
    }
}
