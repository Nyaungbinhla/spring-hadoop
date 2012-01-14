/*
 * Copyright 2011-2012 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.hadoop.fs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ChecksumFileSystem;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Trash;
import org.springframework.data.hadoop.HadoopException;
import org.springframework.data.hadoop.fs.PrettyPrintList.ListPrinter;
import org.springframework.data.hadoop.fs.PrettyPrintMap.MapPrinter;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * HDFS FileSystem Shell supporting the 'hadoop fs/dfs [x]' commands as methods. 
 * See the <a href="http://hadoop.apache.org/common/docs/stable/file_system_shell.html">official guide</a> for more information.
 * <p/>
 * This class mimics as much as possible the shell behavior yet it is meant to be used in a programmatic way, 
 * that is rather then printing out information, they return object or collections that one can iterate through. If the message is
 * all that's needed then simply call the returned object {@link #toString()} explicitly or implicitly (by printing out or doing string
 * concatenation). 
 * 
 * @author Costin Leau
 */
public class FsShell {

	private FileSystem fs;
	private Configuration configuration;
	private Trash trash;


	public FsShell(Configuration configuration) {
		this(configuration, null);
	}

	public FsShell(Configuration configuration, FileSystem fs) {
		this.configuration = configuration;
		try {
			this.fs = (fs != null ? fs : FileSystem.get(configuration));
			this.trash = new Trash(configuration);
		} catch (IOException ex) {
			throw new HadoopException("Cannot create shell", ex);
		}
	}

	private String getContent(InputStream in) throws IOException {
		StringWriter writer = new StringWriter(in.available());
		InputStreamReader reader = new InputStreamReader(in, "UTF-8");

		FileCopyUtils.copy(reader, writer);
		return writer.toString();
	}

	public Collection<Path> cat(String... uris) {
		if (ObjectUtils.isEmpty(uris)) {
			return Collections.emptyList();
		}


		final Collection<Path> results = new PrettyPrintList<Path>(uris.length, new ListPrinter<Path>() {
			@Override
			public String toString(Path e) throws IOException {
				return getContent(fs.open(e));
			}
		});

		try {

			for (String uri : uris) {
				Path src = new Path(uri);
				results.addAll(Arrays.asList(FileUtil.stat2Paths(fs.globStatus(src), src)));
			}
		} catch (IOException ex) {
			throw new HadoopException("Cannot execute command", ex);
		}

		return Collections.unmodifiableCollection(results);
	}

	public void chgrp(String group, String... uris) {
		chgrp(false, group, uris);
	}

	public void chgrpr(String group, String... uris) {
		chgrp(true, group, uris);
	}

	public void chgrp(boolean recursive, String group, String... uris) {
		FsShellPermissions.changePermissions(fs, configuration, FsShellPermissions.Op.CHGRP, recursive, group, uris);
	}

	public void chmod(String mode, String... uris) {
		chmod(false, mode, uris);
	}

	public void chmodr(String mode, String... uris) {
		chmod(true, mode, uris);
	}

	public void chmod(boolean recursive, String mode, String... uris) {
		FsShellPermissions.changePermissions(fs, configuration, FsShellPermissions.Op.CHMOD, recursive, mode, uris);
	}

	public void chown(String mode, String... uris) {
		chown(false, mode, uris);
	}

	public void chownr(String mode, String... uris) {
		chown(true, mode, uris);
	}

	public void chown(boolean recursive, String owner, String... uris) {
		FsShellPermissions.changePermissions(fs, configuration, FsShellPermissions.Op.CHOWN, recursive, owner, uris);
	}

	public void copyFromLocal(String src, String dst) {
		copyFromLocal(src, dst, (String[]) null);
	}

	public void copyFromLocal(String src, String src2, String dst) {
		copyFromLocal(src, src2, new String[] { dst });
	}

	public void copyFromLocal(String src, String src2, String... dst) {
		Assert.hasText(src, "at least one valid source path needs to be specified");

		Path dstPath = null;
		// create src path
		List<Path> srcs = new ArrayList<Path>();
		srcs.add(new Path(src));

		if (!ObjectUtils.isEmpty(dst)) {
			srcs.add(new Path(src2));
			for (int i = 0; i < dst.length - 1; i++) {
				srcs.add(new Path(dst[i]));
			}
			dstPath = new Path(dst[dst.length - 1]);
		}
		else {
			dstPath = new Path(src2);
		}

		try {
			FileSystem dstFs = dstPath.getFileSystem(configuration);
			dstFs.copyFromLocalFile(false, false, srcs.toArray(new Path[srcs.size()]), dstPath);
		} catch (IOException ex) {
			throw new HadoopException("Cannot copy resources", ex);
		}
	}

	public void copyToLocal(String src, String localdst) {
	}

	public void copyToLocal(boolean ignorecrc, boolean crc, String src, String localdst) {
		File dst = new File(localdst);
		Path srcpath = new Path(src);

		try {
			FileSystem srcFs = srcpath.getFileSystem(configuration);
			srcFs.setVerifyChecksum(ignorecrc);
			if (crc && !(srcFs instanceof ChecksumFileSystem)) {
				crc = false;
			}
			FileStatus[] srcs = srcFs.globStatus(srcpath);
			boolean dstIsDir = dst.isDirectory();
			if (srcs.length > 1 && !dstIsDir) {
				throw new IllegalArgumentException("When copying multiple files, "
						+ "destination should be a directory.");
			}
			for (FileStatus status : srcs) {
				Path p = status.getPath();
				File f = dstIsDir ? new File(dst, p.getName()) : dst;
				copyToLocal(srcFs, p, f, crc);
			}
		} catch (IOException ex) {
			throw new HadoopException("Cannot copy resources", ex);
		}

	}

	// copied from FsShell
	private void copyToLocal(final FileSystem srcFS, final Path src, final File dst, final boolean copyCrc)
			throws IOException {

		final String COPYTOLOCAL_PREFIX = "_copyToLocal_";

		/* Keep the structure similar to ChecksumFileSystem.copyToLocal(). 
		* Ideal these two should just invoke FileUtil.copy() and not repeat
		* recursion here. Of course, copy() should support two more options :
		* copyCrc and useTmpFile (may be useTmpFile need not be an option).
		*/
		if (!srcFS.getFileStatus(src).isDir()) {
			if (dst.exists()) {
				// match the error message in FileUtil.checkDest():
				throw new IOException("Target " + dst + " already exists");
			}

			// use absolute name so that tmp file is always created under dest dir
			File tmp = FileUtil.createLocalTempFile(dst.getAbsoluteFile(), COPYTOLOCAL_PREFIX, true);
			if (!FileUtil.copy(srcFS, src, tmp, false, srcFS.getConf())) {
				throw new IOException("Failed to copy " + src + " to " + dst);
			}

			if (!tmp.renameTo(dst)) {
				throw new IOException("Failed to rename tmp file " + tmp + " to local destination \"" + dst + "\".");
			}

			if (copyCrc) {
				if (!(srcFS instanceof ChecksumFileSystem)) {
					throw new IOException("Source file system does not have crc files");
				}

				ChecksumFileSystem csfs = (ChecksumFileSystem) srcFS;
				File dstcs = FileSystem.getLocal(srcFS.getConf()).pathToFile(
						csfs.getChecksumFile(new Path(dst.getCanonicalPath())));
				copyToLocal(csfs.getRawFileSystem(), csfs.getChecksumFile(src), dstcs, false);
			}
		}
		else {
			// once FileUtil.copy() supports tmp file, we don't need to mkdirs().
			dst.mkdirs();
			for (FileStatus path : srcFS.listStatus(src)) {
				copyToLocal(srcFS, path.getPath(), new File(dst, path.getPath().getName()), copyCrc);
			}
		}
	}

	public Map<Path, ContentSummary> count(String... uris) {
		return count(false, uris);
	}

	public Map<Path, ContentSummary> count(final boolean quota, String... uris) {

		final Map<Path, ContentSummary> results = new PrettyPrintMap<Path, ContentSummary>(uris.length,
				new MapPrinter<Path, ContentSummary>() {
					@Override
					public String toString(Path p, ContentSummary c) throws IOException {
						return c.toString(quota) + p;
					}
				});

		for (String src : uris) {
			try {
				Path srcPath = new Path(src);
				final FileSystem fs = srcPath.getFileSystem(configuration);
				FileStatus[] statuses = fs.globStatus(srcPath);
				Assert.notEmpty(statuses, "Can not find listing for " + src);
				for (FileStatus s : statuses) {
					Path p = s.getPath();
					results.put(p, fs.getContentSummary(p));
				}
			} catch (IOException ex) {
				throw new HadoopException("Cannot find listing", ex);
			}
		}

		return Collections.unmodifiableMap(results);
	}

	public void cp(String src, String dst) {
		cp(src, dst, (String[]) null);
	}

	public void cp(String src, String src2, String... dst) {
		Assert.hasText(src, "at least one valid source path needs to be specified");

		Path dstPath = null;
		// create src path
		List<Path> srcs = new ArrayList<Path>();
		srcs.add(new Path(src));


		if (!ObjectUtils.isEmpty(dst)) {
			srcs.add(new Path(src2));
			for (int i = 0; i < dst.length - 1; i++) {
				srcs.add(new Path(dst[i]));
			}
			dstPath = new Path(dst[dst.length - 1]);
		}
		else {
			dstPath = new Path(src2);
		}

		try {

			FileSystem dstFs = dstPath.getFileSystem(configuration);
			boolean isDestDir = dstFs.isDirectory(dstPath);

			if (StringUtils.hasText(src2) || (ObjectUtils.isEmpty(dst) && dst.length > 2)) {
				if (!isDestDir) {
					throw new IllegalArgumentException("When copying multiple files, destination " + dstPath.toUri()
							+ " should be a directory.");
				}
			}

			for (Path path : srcs) {
				FileSystem srcFs = path.getFileSystem(configuration);
				Path[] from = FileUtil.stat2Paths(srcFs.globStatus(path), path);
				if (!ObjectUtils.isEmpty(from) && from.length > 1 && !isDestDir) {
					throw new IllegalArgumentException(
							"When copying multiple files, destination should be a directory.");
				}
				for (Path fromPath : from) {
					FileUtil.copy(srcFs, fromPath, dstFs, dstPath, false, configuration);
				}
			}
		} catch (IOException ex) {
			throw new HadoopException("Cannot copy resources", ex);
		}
	}

	public Map<Path, Long> du(String... uris) {
		return du(false, false, uris);
	}

	public Map<Path, Long> du(boolean summary, boolean humanReadable, String... strings) {
		Assert.notEmpty(strings, "at least one valid path is required");

		final int BORDER = 2;

		Map<Path, Long> results = new PrettyPrintMap<Path, Long>(strings.length, new MapPrinter<Path, Long>() {

			@Override
			public String toString(Path path, Long size) throws Exception {
				return String.format("%-" + (10 + BORDER) + "d", size) + "\n" + path;
			}
		});

		try {
			for (String src : strings) {
				Path srcPath = new Path(src);
				FileSystem srcFs = srcPath.getFileSystem(configuration);
				Path[] pathItems = FileUtil.stat2Paths(srcFs.globStatus(srcPath), srcPath);
				FileStatus items[] = srcFs.listStatus(pathItems);
				if (ObjectUtils.isEmpty(items) && (!srcFs.exists(srcPath))) {
					throw new HadoopException("Cannot access " + src + ": No such file or directory.");
				}
				else {
					int maxLength = 10;

					long length[] = new long[items.length];
					for (int i = 0; i < items.length; i++) {
						Long size = (items[i].isDir() ? srcFs.getContentSummary(items[i].getPath()).getLength() : items[i].getLen());
						results.put(items[i].getPath(), size);
						int len = String.valueOf(length[i]).length();
						if (len > maxLength)
							maxLength = len;
					}
				}
			}
		} catch (IOException ex) {
			throw new HadoopException("Cannot copy resources", ex);
		}

		return results;
	}

	public Map<Path, Long> dus(String... strings) {
		return du(true, false, strings);
	}

	public void expunge() {
		try {
			trash.expunge();
			trash.checkpoint();
		} catch (IOException ex) {
			throw new HadoopException("Cannot expunge trash");
		}
	}

	public void get(String dst, String src) {
		throw new UnsupportedOperationException();
	}

	public void get(boolean ignorecrc, boolean crc, String dst, String src) {
		throw new UnsupportedOperationException();
	}

	public void getmerge(String src, String localdst) {
		getmerge(src, localdst, false);
	}

	public void getmerge(String src, String localdst, boolean addnl) {
		throw new UnsupportedOperationException();
	}

	public String ls(String... args) {
		throw new UnsupportedOperationException();
	}

	public String lsr(String... args) {
		throw new UnsupportedOperationException();
	}

	public void mkdir(String... uris) {
		throw new UnsupportedOperationException();
	}

	public void moveFromLocal(String localsrc, String dst) {
		throw new UnsupportedOperationException();
	}

	public void moveToLocal(String src, String dst) {
		moveToLocal(false, src, dst);
	}

	public void moveToLocal(boolean crc, String src, String dst) {
		throw new UnsupportedOperationException();
	}

	public void mv(String src, String dst) {
		mv(src, null, dst);
	}

	public void mv(String src, String src2, String... dst) {
		throw new UnsupportedOperationException();
	}

	public void put(String localsrc, String dst) {
		throw new UnsupportedOperationException();
	}

	public void put(String localsrc, String localsrc2, String... dst) {
		throw new UnsupportedOperationException();
	}

	public void rm(String... uris) {
		rm(false, uris);
	}

	public void rm(boolean skipTrash, String... uris) {
		throw new UnsupportedOperationException();
	}

	public void rmr(String... uris) {
		rm(false, uris);
	}

	public void rmr(boolean skipTrash, String... uris) {
		throw new UnsupportedOperationException();
	}

	public int setrep(String uri) {
		return setrep(false, uri).iterator().next();
	}

	public Collection<Integer> setrep(boolean recursive, String uri) {
		throw new UnsupportedOperationException();
	}

	public Boolean test(String uri) {
		return test(false, false, false, uri);
	}

	public Boolean test(boolean exists, boolean zero, boolean directory, String uri) {
		throw new UnsupportedOperationException();
	}

	public String text(String uri) {
		throw new UnsupportedOperationException();
	}

	public void touchz(String... uris) {
		throw new UnsupportedOperationException();
	}
}