import xbmc, xbmcgui, xbmcplugin, sys, urllib.parse

MAX_QUERY_LENGTH = 200

handle = int(sys.argv[1])
params = dict(urllib.parse.parse_qsl(sys.argv[2][1:]))

if params.get('action') == 'search':
    kb = xbmc.Keyboard('', 'Search LookMovie')
    kb.doModal()
    if kb.isConfirmed():
        query = kb.getText().strip()[:MAX_QUERY_LENGTH]
        if query:
            qs = urllib.parse.urlencode({'action': 'search', 'query': query})
            xbmc.executebuiltin(
                'RunPlugin(plugin://plugin.video.lookmovie/?{})'.format(qs)
            )
else:
    li = xbmcgui.ListItem('Search LookMovie')
    url = sys.argv[0] + '?action=search'
    xbmcplugin.addDirectoryItem(handle, url, li, False)
    xbmcplugin.endOfDirectory(handle)
