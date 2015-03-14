import java.rmi.*;

public interface SystemCallIf extends Remote {
	public FileInstance getFileVersion ( String path ) throws RemoteException;
	public int writeFile( String path, FilePacket fp ) throws RemoteException;
	public int openFile( FilePacket fp ) throws RemoteException;
	public Chunk readFile( FilePacket fp ) throws RemoteException;
	public int unlinkFile( String path ) throws RemoteException;
}
