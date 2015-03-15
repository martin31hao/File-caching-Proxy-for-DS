/*
 * FileInstance.java
 * This class is used to represent file metadata when dealing with file operations
 *
 * @author  : Xinkai Wang
 * @contact : xinkaiw@andrew.cmu.edu
 */

import java.io.RandomAccessFile;

public class FileInstance implements java.io.Serializable {
    public int fd; // file descriptor
    public String path; // file path
    public String origPath; // the original path passed by client
    public String absPath; // absolute path of the file
    public RandomAccessFile raf; // random access file object
    public String openOption; // open option
    public boolean readOnly; // if the file is readonly
    public boolean canRead; // if the property of the file is made to be readable
    public boolean canWrite; // if the property of the file is made to be writeable
    public boolean isDir = false; // if the file is a directory
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
