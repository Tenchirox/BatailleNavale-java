Un simple jeu de bataille navale, jouable à plusieurs en reseau, jusqu'a 7 joueurs, 
client java lourd ou en mode web avec du javascript, partie serveur en java ou en javascript avec nodeJS.

Comment jouer ?

Le plus simple en locale et d'utiliser la version Java :
java -jar BatailleNavaleServer-v0.4(compatible-web).jar pour lancer le serveur et le client qui va avec BatailleNavaleClient-v0.4(compatible-web).jar

Puis lancer le client (le double clic doit fonctionner si java est correctement installé, sinon se placer dans le repertoire java puis java -jar BatailleNavaleClient-v0.4(compatible-web).jar).

Pour la version web n'importe quel serveur http fera l'affaire ou meme juste ouvrir le index.html dans votre navigateur.

Si vous preferez lancer le serveur avec nodeJS, se placer dans le dossier BattailleNavaleClientWeb,
npm install
node BatailleNavaleServer-node.js

Le cliet lourd java est également disponible avec le serveur node BatailleNavaleClient-v0.4(compatible-web-nodeJs).jar

Le client web utilise une connection websocket et le client lourd passe directement en tcp, les clients web et lourds peuvent jouer enssemble.




