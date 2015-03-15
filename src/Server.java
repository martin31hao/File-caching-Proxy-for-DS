import java.io.*;
import java.net.MalformedURLException;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.Naming;
import java.rmi.RemoteException;

public class Server extends UnicastRemoteObject implements SystemCallIf {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 6613856254761246148L;
	
	private static int listenPort;  // The listening port of the server 
	
	private static String serverRoot = null; // root directory of server files
	
	public static final int chunkSize = 16384; // chunk size is set to 16384

	public Server(String serverRoot) throws RemoteException{
		Server.serverRoot = serverRoot;
	}
	
	/*
	 * Write to content, then add content to buf
	 * @Return: the number of bytes in the buf
	 */
	@Override
	public int writeFile( String path, FilePacket fp ) throws RemoteException {
		
		System.err.println("In write with path: " + path);
		
		String absPath = getServerPath(path);
		
		File file = new File(absPath);
		
		if (file.isDirectory()) {
			return FileHandling.Errors.EISDIR;
		}
		if (!file.canRead() && !file.canWrite()) {
			return FileHandling.Errors.EBADF;
		}
		
		RandomAccessFile rFile = null;
		try {
			rFile = new RandomAccessFile(file, "rw");
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}
		
		if (rFile != null) {
			try {
				rFile.seek(fp.offset);
				rFile.write(fp.content, 0, fp.content.length);
			} catch (IOException e) {
				return FileHandling.Errors.EINVAL;
			}
		}
		
		try {
			rFile.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return fp.content.length;
	}

	/*
	 * Read from Buffered reader, then add content to buf
	 * @Return: the number of bytes in the buf
	 */
	@Override
	public int openFile( FilePacket fp ) throws RemoteException {
		String path = fp.path;
		String o = fp.openOption;
		System.err.println("In read with fd: " + path);
		System.err.println("In open option: " + o);

		String absPath = getServerPath(path); // get absolute path of the file on server
		File file = new File(absPath);
		
		if (o.equalsIgnoreCase("CREATE")) {
			if (!file.isFile()) { // if not exists, then create file
				try {
					System.err.println("CREATE file: " + path);
					file.createNewFile();
				} catch (IOException e) {
					return FileHandling.Errors.ENOMEM; // if new file can't be created, then may be out of mem
				} catch (SecurityException e) {
					return FileHandling.Errors.EPERM; // There is no write permission
				}
			}
		} else if (o.equalsIgnoreCase("CREATE_NEW")) {
			if (!file.isFile()) { // if not exists, then create file
				try {
					System.err.println("CREATE_NEW file: " + path);
					file.createNewFile();
				} catch (IOException e) {
					 // if new file can't be created, then may be out of mem
					return FileHandling.Errors.ENOMEM;
				} catch (SecurityException e) {
					return FileHandling.Errors.EPERM; // There is no write permission
				}
			} else { // if already exists, then return exist error
				return FileHandling.Errors.EEXIST; // if exists, then return EEXIST
			}
		} else if (o.equalsIgnoreCase("WRITE")) {
			if (file.isDirectory()) {  // If it is a dir, then return EISDIR error
				return FileHandling.Errors.EISDIR; 
			}
			if (!file.isFile()) {  // if not exist, then return ENOENT error
				System.err.println("WRITE file not exist: " + path);
				return FileHandling.Errors.ENOENT;
			}
		} else { // "READ" option
			if ((!file.isDirectory()) && (!file.isFile())) { // if not a dir or file, then return ENOENT error
				return FileHandling.Errors.ENOENT;
			}
			System.err.println("READ file not exist: " + path);
			if (file.isDirectory()) {
				return -1024; // -1024 means it is a dir
			}
		}

		int fileLen = (int)(file.length());
		
		if (file.isDirectory()) {
			return FileHandling.Errors.EISDIR;
		}
		
		System.err.println("Before file can read: " + path);
		if (!file.canRead()) {
			return FileHandling.Errors.EBADF;
		}
		
		return fileLen;
		
	}
	
	// Read chunks of bytes
	@Override
	public Chunk readFile( FilePacket fp ) throws RemoteException {
		String path = fp.path;
		System.err.println("In read with path: " + path);

		String absPath = getServerPath(path); // get absolute path of the file on server
		File file = new File(absPath);
		
		RandomAccessFile rFile = null;
		try {
			rFile = new RandomAccessFile(file, "rw");
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}
		
		int byteRead = (int)file.length() - fp.offset;
		if (byteRead > chunkSize)	byteRead = chunkSize;
		
		System.err.println("fp offset: " + fp.offset);
		System.err.println("In the middle of random access: " + path);

		System.err.println("byte to read: " + byteRead);
		
		Chunk result = new Chunk(byteRead);
		
		try {
			System.err.println("result content length: " + result.content.length);
			rFile.seek(fp.offset);
			byteRead = rFile.read(result.content, 0, byteRead);
			System.err.println("Byte read: " + byteRead);
			if (byteRead == -1)	byteRead = 0;
		} catch (IOException e) {
			result.size = FileHandling.Errors.ENOMEM;
			return result;
		} catch (NullPointerException e) {
			result.size = FileHandling.Errors.EINVAL;
			return result;
		}
		
		try {
			rFile.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (byteRead == 0)	result.size = result.content.length;
		else 				result.size = byteRead;
		return result;
	}

	// path: relative path to the server
	@Override
	public int unlinkFile( String path ) throws RemoteException {
		System.err.println("In unlink with path: " + path);
		
		String absPath = getServerPath(path);
		
		File file = new File(absPath);
		if (file.isDirectory()) {
			return FileHandling.Errors.EISDIR;
		}
		if (!file.isFile()) {
			return FileHandling.Errors.ENOENT;
		}
		try {
			boolean deleteRes = file.delete();
			if (!deleteRes) {
				return FileHandling.Errors.EBUSY;
			}
		} catch (SecurityException e) {
			return FileHandling.Errors.EPERM;
		}
		return 0;
	}
	
	public static void main(String[] args) throws IOException {
		/*
		 * args[0]: the listening port of server
		 * args[1]: the root path of server
		 */
		
		Server.listenPort = Integer.parseInt(args[0]);
		Server.serverRoot = args[1];
		
		try {
			LocateRegistry.createRegistry(Server.listenPort);
		} catch (RemoteException e) {
			System.err.println("Failed to create RMI registry");
		}
				
		Server server = null;
		try {
			server = new Server(serverRoot);
		} catch (RemoteException e) {
			System.err.println("Failed to create server " + e);
			System.exit(1);
		}
			
		try {
			Naming.rebind(String.format("//127.0.0.1:%d/ServerService", 
				listenPort), server);
		} catch (RemoteException e) {
			System.err.println(e); //you probably want to do some decent logging here
		} catch (MalformedURLException e) {
			System.err.println(e); //you probably want to do some decent logging here
		}
					
		System.err.println("Server is ready");
	}

	private String getServerPath(String path) {
		StringBuilder sb = new StringBuilder(Server.serverRoot);
		if (Server.serverRoot.charAt(Server.serverRoot.length()-1) != '/') {
			sb.append("/");
		}
		sb.append(path);
		return sb.toString();
	}

	public FilePacket getErrorResult(int errorNum) {
		FilePacket result = new FilePacket();
		result.retVal = errorNum;
		return result;
	}

	@Override
	public FileInstance getFileVersion(String path)
			throws RemoteException {
		
		String absPath = getServerPath(path); // get absolute path of the file on server
		File file = new File(absPath);
		
		File rootPath = new File(this.serverRoot);
		String rootAbs = null;
		try {
			rootAbs = rootPath.getCanonicalPath();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		
		// If the path is out of root dir
		if (file.isFile() || file.isDirectory()) {
			try {
				if (!file.getCanonicalPath().startsWith(rootAbs)) {
					FileInstance fi = new FileInstance(0, 0);
					fi.fileSize = FileHandling.Errors.EPERM;
					return fi;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		FileInstance fi = new FileInstance((int)file.length(), file.lastModified());
		try {
			fi.path = file.getCanonicalPath().substring(rootAbs.length());
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return fi;
	}
}
