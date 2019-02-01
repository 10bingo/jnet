package com.xing.util.log.writer;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.xing.util.log.LogConfig;
import com.xing.util.log.LogConstants;
import com.xing.util.log.LogMessageHolder;
import com.xing.util.log.api.IWriter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;

public class FileWriter implements IWriter, EventHandler<LogMessageHolder> {
    private static FileWriter INSTANCE;
    private static final Object CREATE_LOCK = new Object();
    private Disruptor<LogMessageHolder> disruptor;
    private RingBuffer<LogMessageHolder> buffer;

    private FileOutputStream fileOutputStream;
    private volatile boolean started = false;
    private volatile int fileSize;
    private volatile int lineNum;

    public static FileWriter get() {
        if (INSTANCE == null) {
            synchronized (CREATE_LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new FileWriter();
                }
            }
        }
        return INSTANCE;
    }

    private FileWriter() {
        disruptor = new Disruptor<>(LogMessageHolder::new, 1024, DaemonThreadFactory.INSTANCE);
        disruptor.handleEventsWith(this);
        buffer = disruptor.getRingBuffer();
        lineNum = 0;
        disruptor.start();
    }

    @Override
    public void onEvent(LogMessageHolder event, long sequence, boolean endOfBatch) throws Exception {
        if (hasWriteStream()) {
            try {
                lineNum++;
                write(event.getMessage() + LogConstants.LINE_SEPARATOR, endOfBatch);
            } finally {
                event.setMessage(null);
            }
        }
    }

    private void write(String message, boolean forceFlush) {
        try {
            fileOutputStream.write(message.getBytes());
            fileSize += message.length();
            if (forceFlush || lineNum % 20 == 0) {
                fileOutputStream.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            switchFile();
        }
    }

    private void switchFile() {
        if (fileSize > LogConfig.Logging.MAX_FILE_SIZE) {
            forceExecute(() -> {
                fileOutputStream.flush();
                return null;
            });
            forceExecute(() -> {
                fileOutputStream.close();
                return null;
            });
            forceExecute(() -> {
                new File(LogConfig.Logging.DIR, LogConfig.Logging.FILE_NAME).renameTo(new File(LogConfig.Logging.DIR,LogConfig.Logging.FILE_NAME + new SimpleDateFormat(".yyyy_MM_dd_HH_mm_ss").format(new Date())));
                return null;
            });
            forceExecute(() -> {
                fileOutputStream = null;
                started = false;
                return null;
            });
        }
    }

    private void forceExecute(Callable callable) {
        try {
            callable.call();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean hasWriteStream() {
        if (fileOutputStream != null) {
            return true;
        }
        if (!started) {
            File logFilePath = new File(LogConfig.Logging.DIR);
            if (!logFilePath.exists()) {
                logFilePath.mkdirs();
            } else if (!logFilePath.isDirectory()) {
                System.err.println("Log dir(" + LogConfig.Logging.DIR + ") is not a directory.");
            }
            try {
                fileOutputStream = new FileOutputStream(new File(logFilePath, LogConfig.Logging.FILE_NAME), true);
                fileSize = Long.valueOf(new File(logFilePath, LogConfig.Logging.FILE_NAME).length()).intValue();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            started = true;
        }

        return fileOutputStream != null;
    }

    @Override
    public void write(String message) {
        long next = buffer.next();
        try {
            LogMessageHolder messageHolder = buffer.get(next);
            messageHolder.setMessage(message);
        } finally {
            buffer.publish(next);
        }
    }
}
