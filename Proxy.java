import java.io.*;
import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

class Proxy {
	
	private static String cachePath = null;
	private static String serverip = null;
	private static String serverport = null;
	private static long cacheLim = 0;
	private static List<FileInstance> lruQueue;
	
	// all read copy of a file
	private static HashMap<String, LinkedList<FileInstance>> readCopy;
	
	// HashMap to store which read copy of a fd is referred to
	private static HashMap<Integer, FileInstance> fd2Copy;
	
	// Record the latest version of a file 
	// If the file is not in the cache, then there should be no entry in the hashmap
	private static HashMap<String, Long> fileVersion;  

	// Path to FileInstance in the proxy, for LRU use
	private static HashMap<String, FileInstance> path2fi; 
	
	// global cache size
	private static int cacheSize = 0;
	
	// global fd
	private static int fd = 1000000;

	public static SystemCallIf getServerInstance(String ip, int port) {
	    String url = String.format("//%s:%d/ServerService", ip, port);
	    try {
	      return (SystemCallIf) Naming.lookup(url);
	    } catch (MalformedURLException e) {
	      //you probably want to do logging more properly
	      System.err.println("Bad URL" + e);
	    } catch (RemoteException e) {
	      System.err.println("Remote connection refused to url "+ url + " " + e);
	    } catch (NotBoundException e) {
	      System.err.println("Not bound " + e);
	    }
	    return null;
  	}
	
	private static class FileHandler implements FileHandling {
		
		SystemCallIf server = null;

		private HashMap<Integer, FileInstance> fileMap;
		
		// find the original file instance by file's original path
		// For LRU eviction use

		public FileHandler() {
			fileMap = new HashMap<Integer, FileInstance>();
			
			try {
				server = getServerInstance(Proxy.serverip, Integer.parseInt(Proxy.serverport));
			} catch(Exception e) {
				e.printStackTrace(); //you should actually handle exceptions properly
			}
			
			if (server == null) System.exit(1); //You should handle errors properly.
			
			// make lruQueue a singleton in the proxy
			if (lruQueue == null) {
				synchronized (List.class) {
					if (lruQueue == null) {
						lruQueue = new LinkedList<FileInstance>();
					}
				}
			}
			
			if (fileVersion == null) {
				synchronized (HashMap.class) {
					if (fileVersion == null) {
						fileVersion = new HashMap<String, Long>();
					}
				}
			}
			
			if (readCopy == null) {
				synchronized (HashMap.class) {
					if (readCopy == null) {
						readCopy = new HashMap<String, LinkedList<FileInstance>>();
					}
				}
			}
			
			if (fd2Copy == null) {
				synchronized (HashMap.class) {
					if (fd2Copy == null) {
						fd2Copy = new HashMap<Integer, FileInstance>();
					}
				}
			}
			
			if (path2fi == null) {
				synchronized (HashMap.class) {
					if (path2fi == null) {
						path2fi = new HashMap<String, FileInstance>();
					}
				}
			}
		}

		/*
		 *  Get file name with a directory prefix in front of it
		 */
		private String getDirName(String name) {

			String s[] = name.split("/");
			StringBuilder sb = new StringBuilder(s[1]);
			
			for (int i = 2; i < s.length; i++) {
				sb.append("__"); // Replace / with __ to the file name
				sb.append(s[i]);
			}
			
			return sb.toString();
		}
		
		// Get Cache Path
		private String getCachePath(String path) {
			StringBuilder sb = new StringBuilder(cachePath);
			if (cachePath.charAt(cachePath.length()-1) != '/') {
				sb.append("/");
			}
			sb.append(path);
			return sb.toString();
		}
		
		/*
		 *  Get copy name by append fd to the back of the original path 
		 */
		public synchronized String getNewName(String path) {
			StringBuilder sb = new StringBuilder(path);
			Integer newInt = new Integer(fd);
			sb.append(newInt.toString());
			return sb.toString();
		}
		
		/*
		 * Create a copy of the original one
		 */
		public String createCopy(String path) {
			// Generated new file path
			String newPath = getNewName(path);
			
			// Abs path of original requested file
			String absPath = getCachePath(path);
			
			// copy original file to cur file
			InputStream input = null;
			OutputStream output = null;
			try {
				input = new FileInputStream(absPath);
				output = new FileOutputStream(getCachePath(newPath));
				byte[] buf = new byte[8096];
				int bytesRead;
				while ((bytesRead = input.read(buf)) > 0) {
					output.write(buf, 0, bytesRead);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			finally {
				try {
					input.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				try {
					output.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
			return newPath;
		}
		
		public int open( String path, OpenOption o ) {
			
			try {
				
				String newPath = path; // new path of a file in cache, may be a copy name of the file
				String absPath = getCachePath(path); // absolute path in the cache
				
				FileInstance latestVer = server.getFileVersion(path); // get latest version from server
				
				String serverPath = path; // destination file may be nested directories
				path = getDirName(latestVer.path); // transfer them into a new name in cache
				
				// if the file is not permitted by server
				if (latestVer.fileSize < 0) {
					return latestVer.fileSize;
				}
				
				// Deal with original copy
				synchronized (Proxy.class) {
					if (fileVersion.containsKey(path)) {
						
						// if version is not identical, get it from server again
						if (fileVersion.get(path) != latestVer.modifiedTime) {
							boolean evictRes = evictForFile(latestVer);
							if (evictRes == false) {
								return Errors.ENOMEM;
							}
							int ret = getFileFromServer(path, serverPath, o);
							if (ret < 0 && ret != -1024)	return ret; // 1024 means a directory being opened
							
							// if it is a directory, just return a fd to client
							if (ret == -1024) {
								FileInstance dirFi = new FileInstance(fd, null, null, null, null, null, 0);
								dirFi.isDir = true;
								fileMap.put(fd, dirFi);
								fd++;
								return fd - 1;
							}
							
							// update file version
							fileVersion.put(path, latestVer.modifiedTime);
							
							File file = new File(absPath);
							int size = (int)file.length();
							FileInstance origFi = new FileInstance(-1, path, path, absPath, null, null, size);

							evictFromCache(path2fi.get(path));
							pushIntoCache(origFi);
							path2fi.put(path, origFi);
						} else { // otherwise, just need to update cache
							updateCache(path2fi.get(path));
						}
					} else {  // Else request from the server, as well as create a private copy for this one
						boolean evictRes = evictForFile(latestVer);
						if (evictRes == false) {
							return Errors.ENOMEM;
						}
						
						int ret = getFileFromServer(path, serverPath, o);
						if (ret < 0 && ret != -1024)	return ret;
						
						// if it is a directory, just return a fd to client
						if (ret == -1024) {
							FileInstance dirFi = new FileInstance(fd, null, null, null, null, null, 0);
							dirFi.isDir = true;
							fileMap.put(fd, dirFi);
							fd++;
							return fd - 1;
						}
						
						fileVersion.put(path, latestVer.modifiedTime);
						
						File file = new File(absPath);
						int size = (int)file.length();
						FileInstance origFi = new FileInstance(-1, path, path, absPath, null, null, size);
						pushIntoCache(origFi);
						path2fi.put(path, origFi);
					}
				}
				
				// the mode is read and the read copy exists
				synchronized (Proxy.class) {
					// If there already has read copies
					if (o.name().equalsIgnoreCase("READ") && readCopy.containsKey(path)) {
						FileInstance readFi = readCopy.get(path).getLast();
						// if the latest cache copy is out-dated or the public copy is deleted
						if (readFi.modifiedTime != latestVer.modifiedTime || readFi.readerCnt == 0) {
							boolean evictRes = evictForFile(latestVer);
							if (evictRes == false) {
								return Errors.ENOMEM;
							}
							
							// Create a new copy for READ options
							newPath = createCopy(path); // get the new name of the created copy
							absPath = getCachePath(newPath); // get the absolute cache path of the copy
							
							File file = new File(absPath);
							int size = (int)file.length();
							RandomAccessFile rFile = null;
							try {
								rFile = new RandomAccessFile(file, "rw");
							}  catch (FileNotFoundException e) {
								if (file.isDirectory()) {
									return Errors.EISDIR;
								}
								return Errors.EEXIST; // If the file is not found, then return EEXIST
							} catch (SecurityException e) {
								return Errors.EPERM; // If no permission
							}
							// A read copy here
							FileInstance fi = new FileInstance(fd, newPath, path, absPath, rFile, o.name(), size);
							fi.readOnly = o.name().equalsIgnoreCase("READ");
							fi.modifiedTime = latestVer.modifiedTime;
							// update cache size of the read copy without putting it into LRU
							synchronized (Proxy.class) {
								cacheSize += fi.fileSize;
							}
							fi.readerCnt = 1;
							fileMap.put(fd, fi);
							readCopy.get(path).add(fi);
							fd2Copy.put(fd, fi);
						} else { // if read copy still new
							readFi.readerCnt++;
							File file = new File(readFi.absPath);
							RandomAccessFile rFile = null;
							try {
								rFile = new RandomAccessFile(file, "rw");
							}  catch (FileNotFoundException e) {
								if (file.isDirectory()) {
									return Errors.EISDIR;
								}
								return Errors.EEXIST; // If the file is not found, then return EEXIST
							} catch (SecurityException e) {
								return Errors.EPERM; // If no permission
							}
							newPath = readFi.path;
							absPath = getCachePath(newPath);
							FileInstance fi = new FileInstance(fd, newPath, path, absPath, rFile, o.name(), readFi.fileSize);
							fi.readOnly = o.name().equalsIgnoreCase("READ");
							fileMap.put(fd, fi);
							fd2Copy.put(fd, readFi);
						}
					} else {
						
						boolean evictRes = evictForFile(latestVer);
						if (evictRes == false) {
							System.err.println("Readonly copy not exits ENOMEM");
							return Errors.ENOMEM;
						}
						
						// Create a new copy for non-READ options
						newPath = createCopy(path); // get the new name of the created copy
						absPath = getCachePath(newPath); // get the absolute cache path of the copy
						
						File file = new File(absPath);
						int size = (int)file.length();
						RandomAccessFile rFile = null;
						try {
							rFile = new RandomAccessFile(file, "rw");
						} catch (FileNotFoundException e) {
							if (file.isDirectory()) {
								return Errors.EISDIR;
							}
							return Errors.EEXIST; // If the file is not found, then return EEXIST
						} catch (SecurityException e) {
							return Errors.EPERM; // If no permission
						}
						FileInstance fi = new FileInstance(fd, newPath, path, absPath, rFile, o.name(), size);
						fi.readOnly = o.name().equalsIgnoreCase("READ");
						fi.modifiedTime = latestVer.modifiedTime;
						
						// update cache size without putting it into LRU
						synchronized (Proxy.class) {
							cacheSize += fi.fileSize;
						}
						
						fileMap.put(fd, fi);
						if (fi.readOnly) {
							fi.readerCnt = 1;
							readCopy.put(path, new LinkedList<FileInstance>());
							readCopy.get(path).add(fi);
							fd2Copy.put(fd, fi);
						}
					}
				}
				
				// After file is written or copied to the cache
				fd++;
				return fd - 1;
			}
			catch(RemoteException e) {
				System.err.println(e); //probably want to do some better logging here.
			}
			return fd - 1;
		}

		
		public int close( int fd ) {
			
			/*
			 * When close a fd, push all write updates to server
			 * as well as close fd in the server
			 */
			if (fileMap.get(fd) == null) {
				return Errors.EBADF; // If fd is not valid, then return EBADF
			}
			
			RandomAccessFile rFile = fileMap.get(fd).raf;
			
			try {
				if (rFile != null) {
					// If file is not read only, push updates to server
					// as well as overwrite the original copy in the cache
					if (!fileMap.get(fd).readOnly) {
						
						// write to server 
						int fileLen = (int)((new File(fileMap.get(fd).absPath)).length());
						
						int offset = 0;
						InputStream input = null;
						input = new FileInputStream(fileMap.get(fd).absPath);
						while (true) {
							int byteToWrite = fileLen - offset;
							if (byteToWrite > Server.chunkSize)	byteToWrite = Server.chunkSize;
	
							FilePacket fp = new FilePacket(byteToWrite);
	
							try {
								input.read(fp.content, 0, byteToWrite);
							} catch (Exception e) {
								e.printStackTrace();
							}
							fp.offset = offset;
							int writeLen = server.writeFile(fileMap.get(fd).origPath, fp);
							offset += writeLen;
							if (offset >= fileLen)		break;
						}
						try {
							input.close();
						} catch (IOException e) {
							e.printStackTrace();
						}

						// substract the size from cache
						synchronized (Proxy.class) {
							cacheSize -= fileLen;
						}
						
						//TODO need to test here to see whether need to delete old file
						// delete old file
						int origPathSize = 0;
						String origPath = fileMap.get(fd).origPath;
						String absOrigPath = this.getCachePath(origPath);
						File oldFile = new File(absOrigPath);
						if ((new File(absOrigPath)).isFile()) {
							origPathSize = (int) (new File(absOrigPath)).length();
							oldFile.delete();
						}
						
						// if the original path is in the cache
						// then need to overwrite contents to the cache as well
						// This is because the original file is evicted because of some reason
						//if (fileVersion.containsKey(fileMap.get(fd).origPath)) {
							
						// rename the latest file
						File newFile = new File(fileMap.get(fd).absPath);
						boolean renameSuccess = newFile.renameTo(oldFile);
						if (renameSuccess == false) {
							System.err.println("Rename from " + fileMap.get(fd).absPath + " to " + absOrigPath + " failed.");
						}
						
						// update node size of the original file
						int newPathSize = fileLen;
						path2fi.get(fileMap.get(fd).origPath).fileSize = newPathSize;
						// update cache size
						synchronized (Proxy.class) {
							cacheSize = cacheSize - origPathSize + newPathSize;
							fileVersion.put(fileMap.get(fd).origPath, 
									server.getFileVersion(fileMap.get(fd).origPath).modifiedTime);
						}
						
						// If there is no entry of this file in the cache, then push it into cache
						int idx = lruQueue.indexOf(path2fi.get(fileMap.get(fd).origPath));
						if (idx < 0) {
							path2fi.get(fileMap.get(fd).origPath).fileSize = newPathSize;
							boolean evictRes = evictForFile(path2fi.get(fileMap.get(fd).origPath));
							if (evictRes == false) {
								return Errors.ENOMEM;
							}
							pushIntoCache(path2fi.get(fileMap.get(fd).origPath));
							// Because we have calculated before, so need to subtract it to offset pushIntoCache
							cacheSize -= newPathSize;
						}
						
						//}
					} else { // if read-only, then need to check the read count
						FileInstance closeFi = fileMap.get(fd);
						FileInstance curCopy = fd2Copy.get(fd);
						curCopy.readerCnt--;
						String absPath = closeFi.absPath;
						File oldFile = new File(absPath);
						
						if (curCopy.readerCnt == 0) { // if the reader cnt equals 0, then delete the read copy
							System.err.println("readcnt == 0 " + fileMap.get(fd).origPath);
							// delete public read copy if outdated
							oldFile.delete();
							// substract the size from cache
							synchronized (Proxy.class) {
								cacheSize -= fileMap.get(fd).fileSize;
							}
						}
						fd2Copy.remove(fd);
					}
					
					rFile.close();
					updateCache(path2fi.get(fileMap.get(fd).origPath));
					
				}
				fileMap.remove(fd);
				
				return 0;
			} catch(IOException e) {
				return Errors.EBADF;
			}
		}

		public long write( int fd, byte[] buf ) {
			System.err.println("In write with fd: " + fd);
			
			/*
			 * When write to a fd, write to the copy it owns
			 * Write updates are pushed to server when open-close session ends
			 */
			
			if (fd < 0) {
				return Errors.EINVAL; // If fd < 0, then return EINVAL
			}
			if (fileMap.get(fd) == null) {
				return Errors.EBADF;  // If fd is invalid, then return EBADF
			}
			File file = new File(fileMap.get(fd).absPath);
			if (fileMap.get(fd).isDir) {
				return Errors.EISDIR; // If it is a directory, then return EISDIR
			}
			if (!file.canRead() && !file.canWrite()) {
				return Errors.EBADF;
			}
		 	if (fileMap.get(fd).openOption.equalsIgnoreCase("READ")) {
				return Errors.EBADF;
			}
			RandomAccessFile rFile = fileMap.get(fd).raf;
			int byteWrite = buf.length;
			if (rFile != null) {
				try {
					rFile.write(buf);
				} catch (IOException e) {
					return Errors.EINVAL;
				}
			}

			return byteWrite;
		}

		/*
		 * Read from Buffered reader, then add content to buf
		 * @Return: the number of bytes in the buf
		 */
		public long read( int fd, byte[] buf ) {
			System.err.println("In read with fd: " + fd);
			
			/*
			 * When read to a fd, read to the copy it owns
			 */
			
			if (fd < 0) { // if fd is negative, return EINVAL
				return Errors.EINVAL;
			}
			if (fileMap.get(fd) == null) { // if file descriptor > 0, but don't exist, then return EBADF
				return Errors.EBADF;
			}
			File file = new File(fileMap.get(fd).absPath);
			System.err.println(fileMap.get(fd).absPath);
			if (fileMap.get(fd).isDir) {
				return Errors.EISDIR;  // If it is a directory, then return EISDIR
			}
			
			if (!file.canRead()) {
				return Errors.EBADF;
			}
			RandomAccessFile rFile = fileMap.get(fd).raf;
			int byteRead = 0;
			
			try {
				byteRead = rFile.read(buf);
				if (byteRead == -1)	byteRead = 0;
			} catch (IOException e) {
				System.err.println("Read ENOMEM");
				return Errors.ENOMEM;
			} catch (NullPointerException e) {
				return Errors.EINVAL;
			}
			return byteRead;
		}

		public long lseek( int fd, long pos, LseekOption o ) {
			System.err.println("In lseek with fd: " + fd);
			
			/*
			 * When lseek to a fd, lseek to the copy it owns
			 */
			
			if (fileMap.get(fd) == null) { // If fd is invalid, then return EBADF
				return Errors.EBADF;
			}
			RandomAccessFile rFile = fileMap.get(fd).raf;
			if (o.name().equalsIgnoreCase("FROM_CURRENT")) {
				
			} else if (o.name().equalsIgnoreCase("FROM_END")) {
				try {
					rFile.seek(rFile.length());
				} catch (IOException e) {
					return Errors.EINVAL;
				}
			} else if (o.name().equalsIgnoreCase("FROM_START")) {
				try {
					rFile.seek(0);
				} catch (IOException e) {
					return Errors.EINVAL;
				}
			} else {
				return Errors.EINVAL;
			}
			
			try {
				rFile.seek(rFile.getFilePointer() + pos);
			} catch (IOException e) {
				return Errors.EINVAL;
			}
			try {
				return rFile.getFilePointer();
			} catch (IOException e) {
				return Errors.EINVAL;
			}
		}

		public int unlink( String path ) {
			System.err.println("In unlink with path: " + path);
			
			/*
			 * When unlink to a path, push back unlink updates to server
			 * as well as delete copy in the cache and update LRU
			 */
			
			// need to ensure the path is valid under server root dir
			FileInstance latestVer = null;
			try {
				latestVer = server.getFileVersion(path);
			} catch (RemoteException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			
			String serverPath = path;
			path = getDirName(latestVer.path);
			
			// if the file is not permitted
			if (latestVer.fileSize < 0) {
				return latestVer.fileSize;
			}
			
			String cachePath = this.getCachePath(path);
			
			File file = new File(cachePath);
			
			try {
				int ret = server.unlinkFile(serverPath);
				if (file.isFile()) {
					if (ret == 0)
						fileVersion.remove(path);
					// evict from cache
					evictFromCache(path2fi.get(path));
					file.delete();
				}
				
				return ret;
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			
			return 0;
		}

		public void clientdone() {
			// clean file map and all randomaccessfile refs
			for (Map.Entry<Integer, FileInstance> entry : fileMap.entrySet()) {
				fileMap.remove(entry.getKey());
			}
			return;
		}
		
		// Push fresh file into the front of the cache
		public synchronized void pushIntoCache(FileInstance fi) {
			System.err.println("file " + fi.absPath + " pushed into cache");
			lruQueue.add(0, fi);
			cacheSize += fi.fileSize;
		}
		
		// find the number of file to evict from the cache
		// Then evict them from the back of the cache
		// Return true if there is enough space can be cleared out to contain the new file
		// Return false to let the caller to decide what to do
		public synchronized boolean evictForFile( FileInstance fi ) {
			int tmpSize = 0;
			for (int i = lruQueue.size() - 1; i >= 0; i--) {
				FileInstance queueNode = lruQueue.get(i);
				
				tmpSize += queueNode.fileSize;
				
				if (cacheSize - tmpSize + fi.fileSize <= Proxy.cacheLim)	break;
			}
			if (cacheSize - tmpSize + fi.fileSize > Proxy.cacheLim) {
				return false;
			} else {
				tmpSize = 0;
				for (int i = lruQueue.size() - 1; i >= 0; i--) {
					FileInstance queueNode = lruQueue.get(i);
					
					if (cacheSize - tmpSize + fi.fileSize <= Proxy.cacheLim)	break;
					tmpSize += queueNode.fileSize;
					lruQueue.remove(i);
					System.out.println(queueNode.absPath + " evicted for " + fi.absPath);
					// remove from LRU queue as well as delete the file from cache
					File file = new File(queueNode.absPath);
					file.delete();
					fileVersion.remove(queueNode.path);
				}
			}
			cacheSize -= tmpSize;
			return true;
		}
		
		// evict an item from cache if it's no longer in it
		public synchronized void evictFromCache(FileInstance fi) {
			int idx = lruQueue.indexOf(fi);
			if (idx >= 0) {
				System.err.println("file " + fi.absPath + " evicted from cache");
				lruQueue.remove(idx);
				cacheSize -= fi.fileSize;
			}
		}
		
		// Remove from the origin position first
		public synchronized void updateCache(FileInstance fi) {
			int idx = lruQueue.indexOf(fi);
			if (idx == 0)	return; // If it is already the latest, then no need to update
			if (idx >= lruQueue.size())	return; // If the idx is invalid, then return too
			if (idx < 0)	return;
			FileInstance fileToUpdate = lruQueue.get(idx);
			lruQueue.remove(idx);
			lruQueue.add(0, fileToUpdate);
		}

		// need to ensure that no two client get the identical file in the mean time
		public synchronized int getFileFromServer(String cachePath, String serverPath, OpenOption o) {
			/*
			 * Request the latest version of file from the server in chunks
			 */
			String absPath = getCachePath(cachePath);
			OutputStream out = null;
			try {
				out = new FileOutputStream(absPath);
			} catch (FileNotFoundException e1) {
				e1.printStackTrace();
			}
			
			FilePacket fp = new FilePacket(serverPath, o.name());
			int byteToRead = 0;
			try {
				byteToRead = server.openFile(fp);
			} catch (RemoteException e1) {
				e1.printStackTrace();
			}
			if (byteToRead < 0) { // return value < 0, then there is some error returned by server
				fp.retVal = byteToRead;
				try {
					out.close();
					File file = new File(cachePath);
					file.delete();
				} catch (IOException e) {
					e.printStackTrace();
				}
				return fp.retVal;
			}
			
			// Start to read chunks of file
			fp.offset = 0;
			while (true) {
				Chunk chunk = null;
				try {
					chunk = server.readFile(fp);
				} catch (RemoteException e1) {
					e1.printStackTrace();
				}
				if (chunk.size >= 0) { // return value >= 0, then it is able to read the content of the file
					try {
						out.write(chunk.content, 0, chunk.content.length); // write the whole content in one time
					} catch (IOException e) {
						e.printStackTrace();
					}
				} else { // return value < 0, then there is some error returned by server
					try {
						out.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
					return chunk.size;
				}
				fp.offset += chunk.size;
				if (fp.offset >= byteToRead) // all chunks are read
					break;
			}
			
			try {
				out.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return 0;
		}
	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	public static void main(String[] args) throws IOException {
		serverip = args[0];
		serverport = args[1];
		cachePath = args[2];
		cacheLim = Long.parseLong(args[3]);
		
		while (true) {
			(new RPCreceiver(new FileHandlingFactory())).run();
			
		}
	}
	
}

