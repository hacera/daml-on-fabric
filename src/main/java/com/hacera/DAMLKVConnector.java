// (c) 2019 The Unbounded Network LTD
package com.hacera;

import org.hyperledger.fabric.sdk.BlockEvent;
import org.hyperledger.fabric.sdk.BlockListener;
import org.hyperledger.fabric.sdk.Channel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class DAMLKVConnector {

    public String DEFAULT_LEDGER_ID = "fabric-ledger-server";

    private FabricContext ctx;
    
    private static DAMLKVConnector instance;
    public static synchronized DAMLKVConnector get(boolean doEnsure, boolean doExplorer) {
        if (instance == null) {
            instance = new DAMLKVConnector(doEnsure, doExplorer);
        }
        
        return instance;
    }

    public static synchronized DAMLKVConnector get() {
        return get(false, false);
    }

    private static void logTime(String f, long time) {
        //System.out.format("%s : took %dms%n", f, System.currentTimeMillis()-time);
        //System.out.flush();
    }
    
    private String keyToString(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    private boolean haveBlocks;
    private class DAMLBlockListener implements BlockListener {

        @Override
        public void received(BlockEvent blockEvent) {
            synchronized (DAMLKVConnector.this) {
                haveBlocks = true;
            }
        }
    }

    public boolean checkNewBlocks() {
        boolean rNewBlocks;
        synchronized (this) {
            rNewBlocks = haveBlocks;
            haveBlocks = false;
        }
        return rNewBlocks;
    }

    private DAMLKVConnector(boolean doEnsure, boolean doExplorer) {
        synchronized (this) {
            haveBlocks = true;
        }

        ctx = new FabricContext(doEnsure);
        if (doEnsure) ctx.ensureChaincode();
        if (doExplorer) ExplorerService.Run(ctx);

        // run block checker
        Channel c = ctx.getChannel();
        try {
            c.registerBlockListener(new DAMLBlockListener());
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException) t;
            } else {
                throw new RuntimeException(t);
            }
        }

        if (doEnsure) {
            String id = getLedgerId();
            if (id.isEmpty()) {
                putLedgerId(getLocalLedgerId());
            }
        }
    }

    @FunctionalInterface
    private interface RetryFunction {

        byte[] call();

    }

    // if we have a chaincode invoke exception with invalid transaction - status 10 or 11, this means
    //   that there was a state read/write conflict at the Orderer. we need to retry the transaction
    private boolean checkException(String msg) {
        return (msg != null && (msg.contains("status 11") || msg.contains("status 10")));
    }

    private byte[] retryInvoke(RetryFunction f) {
        // logically, ce cannot be uninitialized here, but Java does not recognize this
        RuntimeException ce = null;

        for (int i = 0; i < 5; i++) {
            try {
                return f.call();
            } catch (RuntimeException e) {

                String msg = e.getMessage();
                // Status 11 means there was some kind of concurrency conflict at the orderer
                if (checkException(msg)) {
                    ce = e;
                    try {
                        Thread.sleep(250);
                        System.out.format("INTERNAL: retrying Fabric transaction...%n");
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);
                    }
                    continue;
                }

                e.printStackTrace(System.err);
                throw e;

            }
        }

        throw ce;
    }
    
    public void putValue(byte[] key, byte[] value) {
        long init = System.currentTimeMillis();
        retryInvoke(() -> ctx.invokeChaincode("RawWrite", key, gzipBytes(value)));
        logTime("putValue", init);
    }

    public byte[] getValue(byte[] key) {
        byte[] data = ctx.queryChaincode("RawRead", new byte[][]{ key });
        if (data != null && data.length > 0) {
            data = gunzipBytes(data);
        }
        if (data.length == 0)
            return null;
        return data;
    }

    public int putCommit(byte[] commit) {
        long init = System.currentTimeMillis();
        logTime("putCommit", init);
        byte[] newIndexBytes = retryInvoke(() -> ctx.invokeChaincode("WriteCommitLog", new byte[][]{ gzipBytes(commit) }));
        int newIndex = ByteBuffer.wrap(newIndexBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return newIndex;
    }
    
    public int getCommitHeight() {

        long init = System.currentTimeMillis();
        byte[] indexBytes = ctx.queryChaincode("ReadCommitHeight");
        logTime("getCommitHeight", init);
        if (indexBytes == null || indexBytes.length == 0)
            return 0;
        int index = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return index;

    }

    public byte[] getCommit(int index) {
        long init = System.currentTimeMillis();
        byte[] data = ctx.queryChaincode("ReadCommit", Integer.toString(index));
        if (data != null && data.length > 0) {
            data = gunzipBytes(data);
        }
        logTime("getCommit", init);
        if (data.length == 0)
            return null;
        return data;
    }
    
    private byte[] gzipBytes(byte[] data) {
        
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream out = new GZIPOutputStream(baos);
            out.write(data);
            out.close();
            return baos.toByteArray();
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException) t;
            } else {
                throw new RuntimeException(t);
            }
        }
        
    }
    
    private byte[] gunzipBytes(byte[] data) {
        
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            GZIPInputStream in = new GZIPInputStream(bais);
            byte[] b = new byte[1024];
            int len;            
            while ((len = in.read(b)) != -1) {
                baos.write(b, 0, len);
            }
            in.close();
            bais.close();
            return baos.toByteArray();
        } catch (Throwable t) {
            if (RuntimeException.class.isAssignableFrom(t.getClass())) {
                throw (RuntimeException) t;
            } else {
                throw new RuntimeException(t);
            }
        }
        
    }
    
    private void putPackage(String cacheKey, byte[] value, boolean cacheOnly) {
        long init = System.currentTimeMillis();
        retryInvoke(() -> ctx.invokeChaincode("PackageWrite", cacheKey.getBytes(StandardCharsets.UTF_8), gzipBytes(value)));
        logTime("putPackage", init);
    }

    public void putPackage(String cacheKey, byte[] value) {
        putPackage(cacheKey, value, false);
    }
    
    public byte[] getPackage(String cacheKey) {
        long init = System.currentTimeMillis();
        byte[] data = ctx.queryChaincode("PackageRead", new byte[][] { cacheKey.getBytes(StandardCharsets.UTF_8) });
        if (data != null && data.length > 0) {
            data = gunzipBytes(data);
        }
        logTime("getPackage", init);
        if (data.length == 0)
            return null;
        return data;        
    }
    
    private byte[] getPackageListBytes() {
        long init = System.currentTimeMillis();
        byte[] data = ctx.queryChaincode("PackageListRead");
        logTime("getPackageListBytes", init);
        return data;
    }
    
    public String[] getPackageList() {
        long init = System.currentTimeMillis();
        byte[] data = getPackageListBytes();
        ByteBuffer dataView = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int packagesCount = dataView.getInt(0);
        String[] packages = new String[packagesCount];
        int offset = 4;
        for (int i = 0; i < packagesCount; i++) {
            int packageLen = dataView.getInt(offset);
            byte[] packageBytes = new byte[packageLen];
            for (int j = 0; j < packageLen; j++) {
                packageBytes[j] = data[offset+4+j];
            }
            packages[i] = new String(packageBytes, StandardCharsets.UTF_8);
            offset += 4 + packageLen;
        }
        logTime("getPackageList", init);
        return packages;
    }

    void putRecordTime(String time) {
        long init = System.currentTimeMillis();
        retryInvoke(() -> ctx.invokeChaincode("RecordTimeWrite", time));
        logTime("putRecordTime", init);
    }

    public String getRecordTime() {
        long init = System.currentTimeMillis();
        byte[] bytes = ctx.queryChaincode("RecordTimeRead");
        logTime("getRecordTime", init);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String getLocalLedgerId() {
        if (ctx == null) return "";
        FabricContextConfig ctxConfig = ctx.getConfig();
        if (ctxConfig == null) return "";
        if (ctxConfig.ledgerId == null) return DEFAULT_LEDGER_ID;
        return ctxConfig.ledgerId;
    }

    void putLedgerId(String ledgerId) {
        long init = System.currentTimeMillis();
        retryInvoke(() -> ctx.invokeChaincode("LedgerIDWrite", ledgerId));
        logTime("putLedgerId", init);
    }

    public String getLedgerId() {
        long init = System.currentTimeMillis();
        byte[] bytes = ctx.queryChaincode("LedgerIDRead");
        logTime("getLedgerId", init);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    public void shutdown() {

    }
    
}
