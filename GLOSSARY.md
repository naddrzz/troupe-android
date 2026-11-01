# Troupe Glossary

Terms follow the Troupe Server's own vocabulary so the app and server speak one language.

**Troupe**
The product. The Android app in this repo is the Troupe mobile client. "Termul" is the former name and never appears in the UI.

**Troupe Server**
The program running on the user's machine (laptop, desktop, VPS) that hosts Projects, Terminals and Agent Chats; the mobile app connects to it. Formerly "termul-server".

**Paired Server**
A Troupe Server the app has been paired with and remembers. The app can hold several and switch between them.
_Avoid_: Desktop, Host, Machine

**Project**
A workspace root on a Troupe Server (one folder). Everything else lives inside a Project.
_Avoid_: Workspace, Repo

**Worktree**
A git worktree belonging to a Project, letting work run in parallel on separate branches.

**Terminal**
A live shell (or agent CLI) running on the server inside a Project.

**Session**
A stored conversation with a coding agent. A Session persists after its agent stops and can be resumed.

**Agent Chat**
The open, live view of one Session, through which the user prompts the agent and sees its messages and tool calls.

**Attention**
Something waiting on the user: a pending permission request or question from an agent, or a stopped chat.

**View-only**
A connection to a Paired Server that may watch but not type, prompt or create anything, because the server does not allow remote writes.
