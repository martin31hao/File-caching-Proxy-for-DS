/*
 * FilePacket.java
 * This class is used to transfer file metadata as well as data between server and proxy
 *
 * @author  : Xinkai Wang
 * @contact : xinkaiw@andrew.cmu.edu
 */

public class FilePacket implements java.io.Serializable{
    public String path = null; // path of the file, used in open operation
    public byte[] content; // byte array for data
    public int retVal; // return value if any
    public String openOption = null; // open option for open operation
    public boolean isDir = false; // check whether a path is directory
    public int offset; // offset of the next chunk to read or write
	
    // This constructor sets the default content length of 1024 for error handling in server
    public FilePacket() {
        content = new byte[1024];
        retVal = 0;
        openOption = null;
    }

    // This constructor is for creating a file
    public FilePacket(int length) {
        content = new byte[length];
        retVal = length;
    }

    // This constructor is for open op at proxy
    public FilePacket(String path, String openOption) {
        this.path = path;
        this.openOption = openOption;
    }
}

