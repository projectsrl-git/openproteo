package com.legalarchive.orchestrator.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;

import com.legalarchive.orchestrator.ds.DataSourceDef;

/**
 * The only part of the IFS content store that touches JTOpen, kept small on purpose: everything with a
 * decision in it lives in {@link IfsContentStore} and is tested with a fake accessor. This class has no
 * decisions, so reviewing it is enough - which it has to be, because there is no AS/400 in the test
 * environment and nothing here has ever been executed.
 *
 * One connection per run, matching {@code IfsSupport}, disconnected on close.
 */
public final class Jt400Ifs implements IfsContentStore.Ifs {

    private final AS400 system;

    public Jt400Ifs(DataSourceDef d) {
        this.system = new AS400(d.host, d.user, d.password);
    }

    public List<IfsContentStore.Entry> list(String dir) throws IOException {
        List<IfsContentStore.Entry> out = new ArrayList<IfsContentStore.Entry>();
        try {
            IFSFile d = new IFSFile(system, dir);
            // a directory that is not there is a miss, not a failure: the caller reports the row as a
            // missing document, which is a data problem the run already knows how to handle
            if (!d.exists() || !d.isDirectory()) return out;
            IFSFile[] kids = d.listFiles();
            if (kids == null) return out;
            for (int i = 0; i < kids.length; i++) {
                IFSFile f = kids[i];
                if (f.isDirectory()) continue;
                out.add(new IfsContentStore.Entry(f.getPath(), f.length(), f.lastModified()));
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("listing the IFS directory " + dir + " failed: " + e.getMessage(), e);
        }
    }

    public IfsContentStore.Entry stat(String path) throws IOException {
        try {
            IFSFile f = new IFSFile(system, path);
            if (!f.exists() || f.isDirectory()) return null;
            return new IfsContentStore.Entry(f.getPath(), f.length(), f.lastModified());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("checking the IFS file " + path + " failed: " + e.getMessage(), e);
        }
    }

    public InputStream open(String path) throws IOException {
        try {
            return new IFSFileInputStream(system, path);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("opening the IFS file " + path + " failed: " + e.getMessage(), e);
        }
    }

    public void close() {
        system.disconnectAllServices();
    }
}
