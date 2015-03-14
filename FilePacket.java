/*
 * FilePacket.java
 * This class is used to transfer file metadata as well as data between server and proxy
 */
public class FilePacket implements java.io.Serializable{
	public String path = null;
	public byte[] content; // byte array for data
	public int retVal; // return value if any
	public String openOption = null; // open option for open operation
	public boolean isDir = false; // check whether a path is directory
	public int offset; // offset of the next chunk to read or write
	
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
	
	public FilePacket(String path, int offset) {
		this.path = path;
		this.offset = offset;
	}
}

