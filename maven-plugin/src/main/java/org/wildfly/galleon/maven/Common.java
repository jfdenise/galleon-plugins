/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.wildfly.galleon.maven;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
public abstract class Common {

    protected static final String CLASS_FILE_EXT = ".class";
    protected static final String JAR_FILE_EXT = ".jar";

    protected static void transformClassFile(final File inClassFile, final File outClassFile) throws IOException {
        if (inClassFile.length() > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException("File " + inClassFile.getAbsolutePath() + " too big! Maximum allowed file size is " + Integer.MAX_VALUE + " bytes");
        }

        final org.wildfly.transformer.Transformer t = org.wildfly.transformer.TransformerFactory.getInstance().newTransformer();
        byte[] clazz = new byte[(int) inClassFile.length()];
        readBytes(new FileInputStream(inClassFile), clazz, true);
        final Transformer.Resource newResource = t.transform(new Transformer.Resource(inClassFile.getName(), clazz));
        clazz = newResource != null ? newResource.getData() : clazz;
        writeBytes(new FileOutputStream(outClassFile), clazz, true);
    }

    protected static void safeClose(final Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (final Throwable t) {
            // ignored
        }
    }

    protected static void readBytes(final InputStream is, final byte[] clazz, final boolean closeStream) throws IOException {
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

    protected static void writeBytes(final OutputStream os, final byte[] clazz, final boolean closeStream) throws IOException {
        try {
            os.write(clazz);
        } finally {
            if (closeStream) {
                safeClose(os);
            }
        }
    }

    protected static void transformJarFile(final File inJarFile, final File outJarFile) throws IOException {
        final org.wildfly.transformer.Transformer t = org.wildfly.transformer.TransformerFactory.getInstance().newTransformer();
        final Calendar calendar = Calendar.getInstance();
        JarFile jar = null;
        JarOutputStream jarOutputStream = null;
        JarEntry inJarEntry, outJarEntry;
        byte[] buffer;
        Transformer.Resource oldResource, newResource;

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
                newResource = t.transform(oldResource);
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
}
