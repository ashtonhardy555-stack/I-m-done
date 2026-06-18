import sys
import urllib.parse

import xbmc
import xbmcgui
import xbmcplugin

LOG_TAG = '[LookMovieSearch] '


def log(msg, level=xbmc.LOGDEBUG):
    xbmc.log(LOG_TAG + msg, level)


def get_handle():
    try:
        return int(sys.argv[1])
    except (IndexError, ValueError) as exc:
        log('Failed to parse plugin handle from argv: %s' % exc, xbmc.LOGERROR)
        raise SystemExit('Plugin handle unavailable')


def get_params():
    try:
        query_string = sys.argv[2][1:]
    except IndexError:
        log('No query string in argv; defaulting to empty params', xbmc.LOGWARNING)
        return {}
    return dict(urllib.parse.parse_qsl(query_string))


def do_search():
    kb = xbmc.Keyboard('', 'Search LookMovie')
    kb.doModal()
    if not kb.isConfirmed():
        log('Search cancelled by user')
        return

    query = kb.getText().strip()
    if not query:
        xbmcgui.Dialog().notification(
            'LookMovie Search',
            'Please enter a search term',
            xbmcgui.NOTIFICATION_WARNING,
        )
        log('Empty search query submitted', xbmc.LOGWARNING)
        return

    encoded = urllib.parse.quote(query)
    log('Searching for: %s' % query)
    xbmc.executebuiltin(
        'RunPlugin(plugin://plugin.video.lookmovie/?action=search&query=%s)' % encoded
    )


def show_directory(handle):
    li = xbmcgui.ListItem('Search LookMovie')
    url = sys.argv[0] + '?action=search'
    xbmcplugin.addDirectoryItem(handle, url, li, False)
    xbmcplugin.endOfDirectory(handle)


def main():
    handle = get_handle()
    params = get_params()

    action = params.get('action')
    if action == 'search':
        do_search()
    elif action is None:
        show_directory(handle)
    else:
        log('Unknown action: %s' % action, xbmc.LOGWARNING)
        xbmcgui.Dialog().notification(
            'LookMovie Search',
            'Unknown action: %s' % action,
            xbmcgui.NOTIFICATION_ERROR,
        )


if __name__ == '__main__':
    try:
        main()
    except SystemExit:
        raise
    except Exception as exc:
        log('Unhandled error: %s' % exc, xbmc.LOGERROR)
        try:
            xbmcgui.Dialog().ok('LookMovie Search', 'An error occurred: %s' % exc)
        except Exception:
            pass
        raise
