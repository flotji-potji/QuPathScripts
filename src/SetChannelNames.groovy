/**
 * Created by Florian Schmidt on 8 Feb 2023.
 */

import static qupath.lib.gui.scripting.QPEx.*
import qupath.lib.objects.classes.PathClass

setChannelNames(
        'DAPI',
        'SMA',
        'CD31',
        'MYH11/NG2',
        'EpCAM',
)

def server = getCurrentServer()
def project = getProject()
def channels = server.getMetadata().getChannels()

List<PathClass> pathClasses = new ArrayList<>()
channels.each {
    pathClasses.add(getPathClass(it.getName()))
}
project.setPathClasses(pathClasses)
project.syncChanges()
