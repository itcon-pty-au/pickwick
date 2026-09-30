"""Read-only local SMB2 fixture. Only exposes the directory passed on the command line."""
import argparse
from impacket import smbserver

parser = argparse.ArgumentParser()
parser.add_argument("directory")
parser.add_argument("--port", type=int, default=1445)
args = parser.parse_args()
server = smbserver.SimpleSMBServer(listenAddress="127.0.0.1", listenPort=args.port)
server.addShare("Videos", args.directory, readOnly="yes")
server.setSMB2Support(True)
server.setLogFile("")
server.start()
