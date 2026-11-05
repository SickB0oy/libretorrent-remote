# LibreTorrent Remote

Version modifiée de [LibreTorrent](https://github.com/proninyaroslav/libretorrent) (licence GPLv3)
qui ajoute une **commande à distance depuis un téléphone** : la TV télécharge, le téléphone pilote
via son navigateur web.

## Ce qui a été ajouté

- Un petit serveur web intégré à l'appli (port 8810 par défaut), accessible depuis n'importe quel
  appareil du même réseau Wi-Fi.
- Une page web adaptée au téléphone : liste des torrents avec progression et vitesses, ajout de liens
  magnet ou de fichiers `.torrent`, pause, reprise, vérification, suppression (avec ou sans les fichiers),
  pause/reprise générale, espace libre restant.
- Protection par mot de passe (généré automatiquement, modifiable).
- Quand la commande à distance est activée, le moteur torrent reste actif en arrière-plan et redémarre
  au démarrage de l'appareil.
- Nom de paquet distinct (`org.proninyaroslav.libretorrent.remote`) : s'installe à côté de
  LibreTorrent officiel sans conflit.

## Utilisation

1. Sur la TV : **Paramètres → Streaming → Commande à distance**, active l'option.
2. Note l'adresse affichée (par exemple `http://192.168.1.20:8810`) et le mot de passe.
3. Sur le téléphone, connecté au même Wi-Fi, ouvre cette adresse dans le navigateur.
   Identifiant : n'importe lequel ; mot de passe : celui affiché sur la TV.
4. Astuce : ajoute la page à l'écran d'accueil du téléphone pour l'ouvrir comme une appli.

## Compilation

L'APK est compilé automatiquement par GitHub Actions à chaque modification de la branche `master`
(workflow `.github/workflows/build-remote.yml`) et publié dans les *Releases* du dépôt.

Deux secrets sont nécessaires (Settings → Secrets and variables → Actions) pour signer l'APK :

- `KEYSTORE_BASE64` : le fichier de clé (`.jks`) encodé en base64 ;
- `KEYSTORE_PASSWORD` : son mot de passe (alias de clé : `libretorrent-remote`).

Garde toujours la même clé : Android refuse d'installer une mise à jour signée avec une autre clé.

## Sécurité

La connexion se fait en HTTP simple sur le réseau local : n'active pas l'option sur un Wi-Fi public
et n'ouvre pas le port sur Internet (pas de redirection de port sur la box).
