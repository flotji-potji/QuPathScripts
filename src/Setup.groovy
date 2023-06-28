/**
 * Created by Florian Schmidt on 8 Feb 2023.
 */

import static qupath.lib.gui.scripting.QPEx.*
import qupath.lib.objects.classes.PathClass

static void main(String[] args) {
    def log = getLogger()

    if (args.length == 0){
        log.error("[-] SetChannelName: no channel names were given")
        return
    }

    setChannelNames(args)

    def server = getCurrentServer()
    def project = getProject()
    def channels = server.getMetadata().getChannels()

    List<PathClass> pathClasses = new ArrayList<>()
    channels.each {
        pathClasses.add(getPathClass(it.getName()))
    }
    project.setPathClasses(pathClasses)
    project.syncChanges()
}

