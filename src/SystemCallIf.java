/*
 * This is the interface which should be implemented by server to achieve RMI
 *
 * @author  : Xinkai Wang
 * @contact : xinkaiw@andrew.cmu.edu
 */

import java.rmi.*;

public interface SystemCallIf extends Remote {
    // Get file version by its modified time
    public FileInstance getFileVersion ( String path ) throws RemoteException;
    
    // Write to a file by its path with content in FilePacket
    public int writeFile( String path, FilePacket fp ) throws RemoteException;
    
    // Open a file by content in FilePacket
    public int openFile( FilePacket fp ) throws RemoteException;
    
    // Read a file by its content in FilePacket
    public Chunk readFile( FilePacket fp ) throws RemoteException;
    
    // Unlink a file by its path
    public int unlinkFile( String path ) throws RemoteException;
}
