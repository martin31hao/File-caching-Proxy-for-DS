import java.io.RandomAccessFile;

/*
 * FileInstance.java
 * This class is used to represent file metadata when dealing with file operations
 */
public class FileInstance implements java.io.Serializable {
	public int fd; // file descriptor
	public String path; // file path
	public String origPath; // the original path passed by client
	public String absPath; // absolute path of the file
	public RandomAccessFile raf; // random access file object
	public String openOption; // open option
	public boolean readOnly; 
	public boolean canRead;
	public boolean canWrite;
	public boolean isDir = false;
	public int fileSize; // file size
	public long modifiedTime; // modified time as the version number of a file
	public int readerCnt = 0; // reader count if the file is read only
	
	public FileInstance(int fd, String path, String origPath, String absPath,
			RandomAccessFile raf, String openOption, int size) {
		this.fd = fd;
		this.path = path;
		this.origPath = origPath;
		this.absPath = absPath;
		this.raf = raf;
		this.openOption = openOption;
		this.readOnly = false;
		this.fileSize = size;
	}
	
	public FileInstance (int size, long modifiedTime) {
		this.fileSize = size;
		this.modifiedTime = modifiedTime;
	}
}
