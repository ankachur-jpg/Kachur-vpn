"""Mobile layout for the GTK desktop client. Run with Python to install it locally."""
from pathlib import Path
import os
import sys
import ast
import tempfile

STYLES = b"""
window.kachur { background: #10142d; color: #f5f6ff; }
.kachur .mobile-title { font-size: 23px; font-weight: bold; }
.kachur .kachur-card, .kachur .kachur-stat { background: #191f3e; border: 1px solid #363f68; }
.kachur .kachur-stat { border-radius: 16px; padding: 10px; }
.kachur .kachur-subtitle, .kachur .kachur-stat-name { color: #bac3e4; }
.kachur .kachur-stat-value { color: #f5f6ff; font-size: 17px; }
.kachur button.kachur-button { min-width: 160px; min-height: 160px; padding: 0; border-radius: 999px;
  border: 3px solid #879af8; font-size: 16px; font-weight: bold; color: white; background: linear-gradient(155deg, #554ccd, #35368c);
  box-shadow: 0 0 0 7px alpha(#697ff0,0.22); }
.kachur button.kachur-button-connected { background: linear-gradient(155deg,#246955,#15372e); border-color: #7df1aa; }
.kachur .mobile-server { padding: 12px 18px; border-radius: 16px; background: #222a50; color: #f5f6ff; }
"""

# Android ui_world_map.xml, based on Natural Earth (public domain).
MAP_PATH = "M112.3,135.8 L115.0,136.7 L110.8,137.5 L105.3,134.8 L108.9,136.1 L110.7,134.5 Z M325.4,122.8 L328.3,122.9 L327.9,125.2 L326.0,125.5 L324.7,123.2 Z M353.0,122.9 L354.2,123.3 L352.7,125.4 L353.1,125.9 L351.5,126.2 L349.3,128.6 L346.7,128.2 L347.0,127.1 Z M354.6,118.2 L356.8,119.9 L358.5,119.7 L355.2,123.7 L354.9,121.9 L353.8,121.5 L354.7,119.4 L352.6,116.5 Z M230.1,95.6 L230.4,97.7 L229.7,97.7 L227.1,106.9 L225.4,107.6 L224.0,107.0 L223.3,104.1 L224.4,102.1 L224.4,98.2 L227.7,96.6 L229.2,94.0 Z M323.6,95.8 L325.4,97.0 L326.4,101.0 L328.8,102.4 L333.1,108.1 L333.6,110.1 L332.9,113.6 L330.0,119.4 L326.3,121.0 L325.0,119.9 L323.6,120.8 L320.6,120.0 L319.6,118.1 L318.1,117.6 L318.2,116.4 L316.8,117.3 L317.8,114.9 L316.0,116.9 L314.3,114.6 L311.3,113.5 L306.1,114.2 L303.7,115.9 L299.9,116.0 L298.0,117.1 L295.0,116.2 L295.7,113.6 L293.3,108.1 L294.2,108.3 L293.4,106.4 L294.1,103.8 L294.2,104.5 L296.7,102.7 L300.9,101.7 L303.0,98.4 L303.9,99.1 L303.5,98.6 L305.7,96.2 L307.1,95.8 L309.6,97.0 L310.6,94.5 L312.6,94.1 L311.8,93.3 L312.4,93.1 L315.3,94.2 L316.5,93.9 L317.0,94.4 L315.5,97.0 L320.2,99.7 L321.3,98.4 L322.5,92.7 Z M288.6,88.8 L290.8,88.5 L295.7,90.4 L294.6,90.8 L285.4,88.9 L286.1,87.9 Z M332.0,87.5 L330.2,88.3 L328.3,87.7 L330.1,87.0 L330.8,87.5 L331.5,86.2 L332.3,86.3 Z M314.1,83.2 L314.4,84.8 L315.5,85.4 L318.3,83.7 L324.6,85.9 L327.6,88.1 L327.2,89.4 L330.7,92.6 L327.9,92.1 L324.7,89.6 L322.6,91.3 L319.1,90.1 L317.6,90.4 L318.7,89.3 L317.9,87.4 L313.7,85.5 L313.0,86.1 L312.0,84.8 L313.7,84.2 L312.2,84.2 L310.5,82.9 L312.4,82.4 Z M305.2,80.6 L303.7,81.8 L300.2,81.8 L300.9,83.4 L303.3,82.6 L301.5,83.9 L303.2,87.3 L302.2,87.3 L302.7,86.5 L301.5,86.6 L301.0,84.6 L300.3,84.9 L300.4,87.5 L299.4,87.4 L299.5,85.5 L298.8,84.8 L300.0,81.4 L300.9,80.7 Z M285.8,87.9 L282.6,86.2 L275.3,76.5 L277.5,76.8 L283.8,81.9 L283.4,82.7 L286.1,85.1 Z M297.9,80.2 L299.0,81.1 L297.8,81.2 L296.1,86.0 L290.2,84.9 L289.1,82.5 L289.7,80.0 L291.2,80.1 L291.4,79.3 L293.0,78.9 L296.7,75.1 L299.2,76.6 L297.3,78.8 Z M306.4,73.6 L306.5,74.8 L306.2,75.7 L305.8,74.7 L305.4,75.2 L305.4,76.4 L304.2,75.8 L303.6,74.2 L301.9,74.8 L303.5,73.3 L305.5,73.0 L305.4,72.2 Z M261.2,75.8 L260.3,76.0 L259.9,75.2 L260.1,72.2 L261.8,74.5 Z M301.3,63.5 L302.2,63.5 L302.5,64.9 L301.7,67.7 L304.0,68.2 L304.1,69.5 L302.9,68.4 L300.6,68.1 L301.0,67.5 L300.1,67.0 L299.9,65.6 L300.3,66.0 L300.7,63.5 Z M107.4,62.1 L110.0,62.4 L111.7,63.4 L109.3,63.6 L108.6,64.4 L105.5,63.7 L107.7,63.3 L106.6,62.4 Z M100.3,59.2 L105.8,61.7 L102.2,62.1 L102.9,61.6 L101.3,60.4 L98.2,59.4 L95.0,60.1 L97.7,58.8 Z M301.2,59.2 L300.7,60.0 L300.1,58.4 L301.5,56.7 Z M321.0,44.9 L320.3,46.9 L317.2,47.4 L315.8,48.5 L315.1,47.4 L311.0,48.1 L312.0,48.9 L311.3,50.5 L310.2,50.6 L310.4,49.7 L309.4,48.7 L312.6,46.6 L315.7,46.5 L316.7,44.7 L317.4,45.2 L319.4,43.8 L319.9,41.4 L321.4,40.6 L321.9,42.8 Z M323.9,37.8 L325.3,37.6 L325.5,38.7 L323.2,40.0 L321.6,39.3 L321.1,40.4 L320.0,40.4 L319.8,39.4 L321.4,38.6 L322.0,36.4 Z M56.5,33.5 L54.3,33.2 L51.6,31.2 L54.2,31.7 Z M123.9,31.3 L123.2,32.2 L126.5,32.8 L126.2,33.5 L126.9,33.3 L127.4,34.5 L126.9,35.3 L125.8,35.2 L125.8,34.2 L124.6,35.1 L123.7,34.4 L120.7,34.4 L122.6,31.3 L124.6,30.4 Z M323.6,31.3 L324.7,33.0 L323.2,32.7 L322.6,34.1 L323.5,35.9 L322.7,35.3 L322.1,36.0 L321.6,30.1 L322.2,27.8 Z M173.2,29.7 L170.0,30.2 L170.8,29.1 L170.3,28.1 L173.3,26.8 L174.3,27.4 Z M177.0,23.4 L175.9,24.4 L178.0,24.3 L176.9,26.0 L177.9,26.1 L180.5,29.1 L181.7,29.3 L181.4,30.7 L174.8,32.0 L174.2,31.8 L176.6,30.6 L174.7,30.0 L175.8,29.7 L175.4,28.5 L177.1,28.0 L175.2,27.2 L175.0,26.2 L174.4,26.7 L173.9,25.2 L175.0,23.4 Z M94.8,16.3 L99.9,18.3 L96.9,17.9 L94.5,18.9 L92.8,18.5 L94.1,16.3 Z M165.5,15.5 L165.3,16.2 L166.4,16.9 L161.3,18.5 L157.2,18.0 L158.2,17.6 L156.0,17.1 L157.8,16.6 L155.7,16.4 L157.9,15.6 L159.4,16.3 Z M5.0,15.4 L8.1,15.1 L10.1,16.0 L7.5,16.6 L7.0,17.7 L1.6,16.6 L1.3,15.9 L0.1,16.1 L0.0,17.0 L0.0,13.0 L5.1,14.8 Z M89.5,12.5 L89.4,13.5 L90.8,12.7 L92.6,14.8 L94.4,13.2 L94.5,12.1 L97.4,12.3 L98.7,12.8 L98.0,13.9 L98.7,14.4 L96.7,15.6 L94.2,15.4 L92.7,17.2 L86.8,20.0 L85.3,23.1 L86.8,23.2 L87.7,24.9 L97.7,26.9 L97.9,28.7 L100.1,30.8 L101.4,29.4 L100.2,27.3 L103.5,25.5 L101.5,23.2 L102.7,22.1 L101.9,19.7 L106.2,19.6 L110.4,20.9 L110.7,23.0 L112.4,23.8 L115.4,21.7 L118.6,25.0 L118.2,25.7 L122.7,27.4 L124.2,28.7 L124.3,29.9 L120.0,31.8 L113.6,31.8 L108.9,35.2 L114.9,32.8 L115.8,33.3 L114.9,33.9 L115.5,35.8 L118.5,36.1 L119.5,35.0 L120.2,36.1 L114.6,38.5 L113.9,38.4 L113.8,37.5 L115.6,36.7 L112.9,36.9 L109.3,39.0 L110.0,40.4 L106.3,41.1 L108.1,41.1 L106.0,41.2 L105.1,43.1 L104.5,42.5 L104.9,43.6 L104.1,44.8 L103.7,42.8 L103.7,43.9 L103.0,43.8 L104.3,46.4 L98.7,50.6 L99.9,55.1 L99.6,56.8 L98.3,56.1 L97.1,52.9 L95.9,51.9 L90.8,51.7 L90.8,52.7 L89.8,52.9 L86.2,52.3 L83.4,53.7 L82.6,54.6 L82.1,59.6 L83.7,62.7 L85.6,63.9 L88.0,63.3 L89.2,62.7 L89.7,61.0 L92.9,60.5 L91.1,66.1 L95.0,66.0 L96.6,66.7 L96.2,70.9 L98.6,73.2 L100.4,72.4 L103.2,73.4 L105.1,70.9 L108.2,69.6 L108.9,69.9 L108.1,70.6 L108.3,72.9 L109.0,72.1 L108.6,71.0 L110.1,69.8 L111.8,71.4 L115.1,71.9 L118.1,71.3 L117.3,71.6 L117.6,72.1 L122.9,76.0 L126.0,76.2 L128.7,77.8 L130.0,80.3 L129.6,82.1 L131.4,82.2 L131.4,83.2 L132.2,82.6 L135.1,83.6 L135.4,84.7 L140.0,84.9 L144.4,87.1 L145.3,89.3 L144.9,91.0 L141.3,95.1 L140.7,99.9 L139.1,103.9 L138.0,105.0 L132.4,106.9 L131.1,110.7 L126.2,116.4 L123.8,116.9 L121.6,115.9 L123.2,118.9 L120.8,120.7 L117.7,120.8 L117.9,122.7 L117.3,123.0 L114.9,123.1 L115.0,124.1 L116.5,124.6 L114.8,125.5 L114.4,127.0 L112.7,127.6 L112.4,128.3 L114.4,129.2 L114.0,130.1 L110.9,132.7 L111.8,134.3 L109.2,134.9 L109.0,135.8 L105.1,134.3 L104.4,130.7 L105.9,128.9 L104.4,128.6 L105.6,126.1 L106.8,126.5 L107.3,124.4 L106.6,124.1 L106.3,125.4 L105.7,125.2 L106.8,121.3 L106.4,119.2 L108.6,114.4 L109.8,101.8 L108.5,99.4 L104.0,96.6 L100.2,89.2 L98.8,88.1 L98.6,86.7 L100.2,84.7 L99.0,84.2 L99.1,83.1 L102.9,78.2 L101.8,73.7 L100.4,73.1 L99.1,74.8 L94.3,72.1 L92.5,68.7 L88.8,68.1 L85.3,65.8 L83.4,66.3 L76.5,63.7 L74.5,62.1 L74.0,59.2 L67.8,53.0 L66.9,50.8 L65.2,50.2 L65.3,51.8 L70.6,58.8 L70.0,59.2 L67.8,57.3 L67.7,56.0 L64.9,54.3 L65.8,53.4 L64.5,52.4 L62.7,49.0 L59.4,47.4 L55.6,41.7 L56.1,36.5 L55.3,33.8 L56.9,34.0 L57.4,34.9 L57.2,33.0 L52.6,31.2 L52.1,29.7 L50.9,29.2 L45.9,23.9 L32.9,21.1 L31.8,21.3 L32.0,22.0 L28.3,22.8 L29.4,20.7 L26.0,22.6 L26.7,23.1 L25.8,23.9 L21.6,26.0 L15.2,27.6 L22.3,24.4 L23.0,23.1 L18.0,23.3 L18.1,22.4 L16.2,22.2 L13.9,20.5 L15.4,18.9 L19.2,18.2 L18.5,17.6 L19.2,17.2 L15.0,17.6 L11.9,16.3 L15.5,15.4 L18.3,15.9 L13.2,13.6 L18.1,11.7 L23.4,10.6 L43.5,13.1 L51.9,11.5 L54.2,12.5 L55.6,11.8 L55.7,12.6 L58.5,12.2 L66.1,13.6 L64.7,14.1 L71.1,14.6 L72.2,14.1 L71.2,13.7 L71.8,13.3 L73.8,13.2 L78.5,14.4 L81.6,14.2 L82.3,13.4 L83.9,13.8 L83.9,14.7 L85.8,12.9 L83.5,11.9 L83.6,10.8 L84.8,10.1 L88.5,11.8 L87.6,12.3 Z M65.8,8.9 L65.3,9.3 L70.1,9.0 L71.8,10.3 L72.3,9.9 L71.6,8.9 L73.5,8.9 L75.5,11.0 L78.9,12.4 L77.3,12.5 L77.6,13.2 L66.7,13.5 L62.7,12.0 L67.6,11.6 L62.1,11.5 L61.6,11.1 L63.9,10.7 L60.6,10.4 L62.1,9.3 Z M93.4,8.8 L94.2,9.5 L97.7,8.2 L99.4,9.3 L99.3,9.9 L102.2,9.3 L105.9,10.7 L107.8,10.4 L113.0,12.8 L111.2,13.3 L118.1,15.1 L116.1,17.0 L112.0,15.7 L115.3,18.6 L115.0,19.3 L111.2,18.3 L113.8,20.1 L109.0,19.1 L105.2,17.3 L102.3,17.8 L101.4,17.4 L102.1,16.7 L106.0,16.5 L107.1,14.3 L101.0,11.8 L95.1,12.0 L90.1,10.8 L89.8,9.8 L90.6,8.9 L94.2,8.2 Z M79.6,8.2 L82.6,8.2 L81.9,9.0 L83.5,9.4 L83.3,10.3 L80.7,10.6 L77.5,9.5 L79.6,9.3 L78.5,8.6 Z M86.8,9.2 L84.6,9.9 L84.0,8.6 L85.5,7.9 L89.5,8.1 Z M59.5,10.6 L56.9,11.1 L54.1,10.1 L56.1,8.3 L55.1,7.7 L62.4,7.8 L64.5,8.5 L60.8,9.5 Z M325.1,6.4 L324.3,7.2 L319.0,7.4 L317.0,6.7 L318.8,5.9 Z M81.5,5.3 L82.3,5.7 L81.8,7.0 L77.5,6.4 L77.4,5.7 Z M71.8,5.8 L74.3,6.5 L67.8,7.6 L66.1,7.3 L68.2,6.8 L62.3,6.8 L64.6,5.5 L70.9,6.5 L69.5,5.6 Z M237.5,11.3 L233.7,11.2 L231.5,10.0 L235.9,7.4 L235.6,6.9 L241.2,5.7 L248.9,5.5 L238.5,7.7 L235.4,9.6 L235.6,10.5 Z M85.3,4.9 L90.8,6.4 L98.9,6.3 L100.2,7.1 L90.2,7.5 L87.6,7.2 L86.1,5.7 L82.9,5.2 Z M63.8,4.4 L62.9,5.5 L57.1,5.9 L60.9,4.5 Z M287.0,5.0 L294.1,6.2 L289.4,7.8 L303.2,9.0 L303.3,8.3 L307.0,8.4 L308.6,9.0 L309.1,9.6 L308.5,10.0 L311.3,11.2 L312.3,10.2 L319.9,10.5 L319.1,9.6 L320.5,9.2 L329.5,9.8 L333.0,11.2 L339.0,11.1 L340.9,12.6 L347.8,12.4 L349.6,13.3 L350.8,13.0 L350.0,12.3 L350.5,11.9 L355.7,12.1 L360.0,13.0 L360.0,17.0 L357.4,17.4 L359.4,19.0 L359.2,19.7 L353.7,20.3 L350.3,22.1 L348.9,21.4 L343.5,22.1 L342.0,23.8 L343.2,24.4 L343.1,25.8 L341.7,26.7 L342.1,27.1 L340.4,27.7 L340.0,28.8 L338.5,29.0 L336.8,31.0 L335.4,26.6 L335.9,25.2 L343.7,20.9 L344.5,19.4 L340.1,21.5 L339.3,20.2 L336.7,20.6 L334.2,22.2 L335.0,22.9 L331.3,23.2 L331.3,22.5 L329.8,22.3 L322.2,23.0 L315.1,27.3 L318.2,28.2 L319.9,27.8 L321.4,29.8 L320.1,33.6 L318.2,35.7 L314.9,38.6 L313.5,39.2 L312.3,38.7 L307.5,42.2 L309.5,45.2 L309.1,46.9 L306.5,47.6 L306.1,45.3 L306.9,45.1 L304.7,43.9 L305.3,42.4 L304.3,42.1 L301.1,43.1 L302.2,41.6 L301.6,41.1 L298.0,42.8 L297.5,43.3 L298.9,44.6 L302.4,44.5 L299.2,47.1 L301.9,50.3 L301.3,51.3 L302.1,52.2 L301.7,53.8 L298.7,57.5 L295.9,59.2 L290.8,60.6 L290.4,61.7 L289.9,60.6 L288.5,60.3 L285.9,62.2 L285.7,62.9 L288.9,66.7 L289.3,68.6 L289.2,70.3 L285.2,73.4 L285.1,72.1 L280.1,68.6 L279.2,72.8 L283.0,76.5 L284.2,80.7 L281.4,79.2 L280.1,75.5 L278.5,73.6 L278.3,74.2 L278.8,70.6 L277.2,65.1 L275.4,66.3 L274.2,66.0 L274.3,63.8 L271.4,59.2 L270.5,59.2 L270.3,60.2 L267.0,60.5 L266.5,61.8 L260.3,66.1 L259.9,71.6 L257.5,74.0 L253.5,66.0 L252.6,60.6 L250.5,61.1 L246.4,56.6 L237.4,56.3 L236.5,54.9 L234.7,55.5 L231.5,54.1 L230.1,51.9 L228.0,52.0 L230.8,57.2 L231.0,56.0 L231.6,56.2 L231.8,58.0 L234.0,57.9 L236.4,55.6 L236.8,57.8 L239.8,59.7 L237.8,61.8 L237.7,63.1 L235.3,64.8 L228.7,68.0 L223.5,69.4 L222.6,65.2 L219.1,60.7 L218.5,58.3 L214.6,53.9 L214.9,52.5 L213.9,54.4 L212.4,52.1 L215.5,58.9 L216.9,60.0 L217.5,63.4 L219.3,66.1 L223.3,69.6 L222.7,70.3 L224.6,71.6 L231.1,70.0 L231.0,71.4 L227.7,77.8 L220.3,84.6 L219.2,86.7 L218.8,88.5 L220.5,92.8 L220.8,96.7 L219.5,98.7 L214.8,101.8 L215.6,105.7 L212.6,107.7 L212.2,110.8 L208.2,114.8 L205.8,115.9 L202.6,115.9 L199.6,116.8 L198.4,116.1 L198.2,113.7 L195.2,109.1 L194.3,104.1 L191.8,100.1 L191.8,97.8 L193.7,92.7 L191.9,87.0 L188.8,83.1 L189.4,78.3 L188.5,77.2 L185.9,77.7 L184.3,75.7 L178.0,77.3 L171.0,77.2 L167.6,74.7 L163.4,69.8 L162.4,67.3 L163.9,63.9 L163.0,60.1 L165.6,55.7 L170.4,52.1 L170.7,49.4 L173.1,47.9 L174.1,46.2 L177.8,46.8 L181.5,45.4 L189.5,44.6 L191.1,45.1 L190.3,48.2 L199.1,51.7 L200.1,49.8 L201.5,49.2 L208.9,51.1 L211.0,50.4 L213.8,51.0 L216.0,47.4 L216.2,45.3 L212.5,45.9 L207.6,45.3 L206.2,42.5 L209.2,40.8 L213.5,40.0 L218.3,41.1 L220.4,41.0 L221.7,40.0 L216.7,36.8 L219.1,34.7 L215.0,35.7 L216.3,36.9 L213.9,37.6 L212.5,36.7 L213.3,35.9 L210.7,35.4 L207.7,39.4 L208.8,40.9 L206.4,41.8 L204.9,41.1 L203.7,41.3 L204.4,41.9 L203.9,42.0 L202.6,41.7 L204.0,44.3 L203.1,44.1 L203.2,45.6 L202.5,45.6 L199.4,41.7 L199.5,40.3 L193.1,36.3 L192.3,36.6 L192.6,37.9 L198.5,41.8 L196.9,41.6 L197.1,43.1 L196.1,44.0 L195.4,42.0 L188.9,37.6 L186.5,38.9 L183.1,38.9 L183.0,40.1 L180.8,41.0 L179.7,42.7 L180.1,43.3 L177.9,45.3 L174.6,46.1 L173.5,45.1 L171.1,45.1 L170.6,39.0 L172.0,38.3 L178.1,38.6 L178.6,38.0 L178.8,36.0 L175.4,33.3 L178.4,33.4 L178.1,32.2 L179.0,32.7 L181.3,31.9 L181.6,31.1 L183.8,30.4 L184.7,28.9 L188.1,28.5 L188.8,28.0 L188.1,26.5 L188.5,24.9 L190.6,24.3 L190.3,25.1 L190.9,25.5 L189.7,26.5 L190.9,28.0 L192.5,27.5 L194.1,28.2 L197.6,27.1 L199.7,27.6 L201.3,26.8 L201.6,24.6 L204.1,25.0 L204.4,23.6 L203.3,22.8 L209.1,22.0 L202.9,22.2 L201.3,21.3 L201.5,18.8 L205.4,16.9 L203.9,16.0 L202.2,16.3 L201.4,17.6 L197.8,19.3 L197.1,20.7 L198.8,21.9 L196.8,23.3 L195.9,25.9 L192.9,26.6 L190.4,22.5 L188.4,23.7 L185.7,23.4 L185.0,20.0 L190.5,17.5 L194.8,14.2 L199.2,12.2 L204.5,11.0 L208.2,10.8 L211.3,11.5 L210.0,11.8 L211.1,12.4 L216.5,12.9 L221.1,14.5 L221.1,15.2 L218.4,16.0 L213.2,15.4 L214.8,16.1 L214.9,17.6 L217.0,18.2 L216.5,17.2 L217.2,16.9 L219.6,17.5 L220.4,17.2 L219.8,16.5 L222.1,15.5 L223.9,15.9 L224.5,15.2 L223.5,13.4 L226.3,13.7 L226.8,14.3 L225.6,15.0 L226.3,15.3 L233.7,13.1 L234.5,13.2 L233.5,13.8 L238.8,13.1 L239.9,13.7 L241.1,13.1 L240.0,12.5 L240.6,12.1 L248.5,13.9 L249.2,13.4 L246.9,12.5 L246.7,11.0 L249.2,9.2 L252.6,9.2 L251.8,10.6 L252.8,11.6 L252.6,13.0 L253.7,13.6 L251.3,15.7 L252.4,15.8 L255.1,14.2 L254.5,13.7 L254.9,13.0 L253.6,12.4 L254.4,11.4 L253.1,10.6 L254.9,9.9 L254.7,9.2 L255.7,9.7 L255.3,10.7 L256.4,10.8 L255.9,10.1 L257.6,9.7 L261.5,10.2 L260.5,8.4 L266.8,8.1 L266.0,7.5 L267.2,6.9 L280.8,5.6 L284.4,4.3 Z M229.1,40.7 L230.4,41.7 L228.9,43.2 L229.2,44.4 L233.8,45.0 L233.9,43.0 L232.7,42.0 L232.9,41.1 L234.7,41.0 L233.7,39.9 L232.8,40.9 L232.5,39.2 L230.3,37.4 L233.0,36.7 L233.0,35.1 L229.1,35.6 L226.7,37.4 Z M79.9,3.7 L80.3,4.1 L74.8,3.6 L75.8,3.3 L74.5,2.7 Z M285.1,3.7 L279.4,4.1 L282.1,2.7 Z M198.3,2.3 L201.5,3.0 L199.0,3.4 L197.1,5.2 L195.9,5.2 L190.4,2.3 Z M205.4,1.6 L207.4,1.9 L203.0,2.6 L197.4,1.7 Z M279.9,3.1 L275.0,3.0 L271.2,1.7 L275.9,0.7 L280.2,2.2 Z M93.0,2.3 L94.2,2.7 L89.2,3.8 L87.1,3.7 L86.0,3.2 L86.9,2.6 L83.3,1.8 L85.3,0.8 L87.6,0.7 Z M111.5,-1.1 L118.2,-0.6 L112.3,0.5 L114.5,0.5 L108.8,2.2 L103.1,2.7 L104.5,2.8 L103.8,3.0 L104.6,3.5 L100.2,4.8 L102.1,5.2 L99.4,5.8 L90.5,5.5 L92.2,4.8 L91.7,4.1 L95.0,4.5 L92.0,3.6 L94.9,2.7 L93.1,1.7 L98.2,1.5 L92.4,1.5 L88.4,0.1 L100.7,-1.1 Z M152.9,-1.5 L159.2,-0.7 L148.1,-0.2 L157.9,0.3 L156.8,0.8 L164.2,0.1 L167.8,0.7 L160.0,1.8 L162.3,1.9 L160.3,3.2 L160.3,4.4 L161.5,5.0 L158.3,5.4 L160.2,5.9 L160.4,6.8 L159.3,6.8 L160.6,7.7 L156.4,8.7 L157.7,9.8 L155.2,9.7 L157.9,10.5 L158.2,11.3 L156.5,11.5 L154.5,10.6 L154.8,11.2 L153.6,11.8 L157.7,11.9 L140.2,16.5 L137.2,19.3 L137.6,20.1 L136.6,21.9 L131.7,21.1 L128.4,18.4 L126.0,14.8 L129.1,12.1 L125.3,12.4 L125.6,11.2 L128.6,11.4 L124.2,10.3 L125.3,9.4 L121.4,6.5 L111.5,5.9 L108.6,5.0 L113.2,4.6 L106.7,4.0 L114.3,2.6 L114.7,2.2 L112.0,1.9 L117.8,0.7 L117.3,0.2 L129.6,-0.4 L135.5,0.3 L133.2,-0.6 L141.4,-1.5 Z"


def apply(window):
    from gi.repository import Adw, Gdk, Gio, GLib, Gtk

    toolbar = window.get_content()
    old_scroll = toolbar.get_content()
    body = window.power.get_parent().get_parent()
    if not isinstance(body, Gtk.Box) or not isinstance(old_scroll, Gtk.ScrolledWindow):
        raise RuntimeError("Ожидается версия клиента Fedora с главным экраном GTK4.")

    def move(widget, target):
        widget.get_parent().remove(widget)
        target.append(widget)

    # Capture the original banner and statistic tiles before moving the controls.
    hero = window.power.get_parent()
    artwork = hero.get_first_child()
    traffic = window.rx.get_parent().get_parent()
    header = Adw.HeaderBar()
    top = Gtk.Box(spacing=6)
    top.set_valign(Gtk.Align.CENTER)
    if isinstance(artwork, (Gtk.Picture, Gtk.Image)):
        move(artwork, top)
        artwork.set_size_request(44, 44)
        if isinstance(artwork, Gtk.Image):
            artwork.set_pixel_size(36)
    title = Gtk.Label(label="Kachur VPN")
    title.add_css_class("mobile-title")
    top.append(title)
    header.set_title_widget(top)

    provider = Gtk.CssProvider()
    provider.load_from_data(STYLES)
    Gtk.StyleContext.add_provider_for_display(
        Gdk.Display.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1
    )
    # Keep the original scroll and all additional controls from locally installed builds.
    toolbar.set_content(None)
    frame = Adw.ToolbarView()
    frame.add_top_bar(header)
    window.set_content(frame)
    pages = Gtk.Stack()
    pages.set_vhomogeneous(False)
    pages.set_hhomogeneous(False)
    pages.set_vexpand(True)
    footer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
    frame.set_content(footer)
    footer.append(pages)

    def page(name, caption):
        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        for edge in ("top", "bottom", "start", "end"):
            getattr(content, "set_margin_" + edge)(16)
        scroller.set_child(content)
        pages.add_titled(scroller, name, caption)
        return content

    home = page("home", "VPN")
    diag = page("diagnostics", "Диагностика")
    servers = page("profiles", "Серверы")
    pages.add_named(old_scroll, "settings")
    # Strip the old heading and subtitle, keeping unknown version-specific settings intact.
    first = body.get_first_child()
    while isinstance(first, Gtk.Label) and first is not window.message:
        body.remove(first)
        first = body.get_first_child()

    stage = Gtk.Overlay()
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="420" height="198" viewBox="0 0 360 143">'
           '<defs><radialGradient id="g"><stop stop-color="#425ec3"/>'
           '<stop offset="1" stop-color="#192142"/></radialGradient></defs>'
           '<path fill="url(#g)" fill-rule="evenodd" d="' + MAP_PATH + '"/></svg>')
    from gi.repository import GdkPixbuf
    stream = Gio.MemoryInputStream.new_from_bytes(GLib.Bytes.new(svg.encode()))
    map_image = Gtk.Picture.new_for_pixbuf(GdkPixbuf.Pixbuf.new_from_stream(stream, None))
    map_image.set_content_fit(Gtk.ContentFit.CONTAIN)
    map_image.set_size_request(-1, 198)
    stage.set_child(map_image)
    home.append(stage)
    hero.remove(window.power)
    window.power.set_halign(Gtk.Align.CENTER)
    window.power.set_valign(Gtk.Align.CENTER)
    stage.add_overlay(window.power)
    move(window.status, home)
    if hero.get_first_child() is None:
        body.remove(hero)

    server = Gtk.Button(label="Профиль  ·  " + window.profile.get_text() + "    ›")
    server.add_css_class("mobile-server")
    server.connect("clicked", lambda *_: pages.set_visible_child_name("profiles"))
    home.append(server)
    # Handshake is diagnostic data; the traffic view shows the actual kernel counters.
    handshake_card = window.handshake.get_parent()
    move(handshake_card, diag)
    move(traffic, home)
    traffic.set_homogeneous(True)
    move(window.profile.get_parent(), servers)
    import_hint = window.import_button.get_next_sibling()
    move(window.import_button, servers)
    if isinstance(import_hint, Gtk.Label) and import_hint is not window.bypass_summary:
        move(import_hint, servers)
    for button in (window.handshake_button, window.connection_button,
                   window.offline_test_button, window.refresh_button):
        move(button, diag)
    # Move the help for offline tests from the old settings form.
    child = body.get_first_child()
    while child is not None:
        following = child.get_next_sibling()
        if isinstance(child, Gtk.Label) and child.get_text().startswith((
            "Автономный тест ", "Автообновление счётчиков ", "Автономная проверка "
        )):
            move(child, diag)
        child = following
    # Keep action feedback visible from every tab.
    move(window.message, footer)
    window.message.set_margin_start(12)
    window.message.set_margin_end(12)
    window.message.connect("notify::label", lambda w, *_: w.set_visible(bool(w.get_text())))
    window.message.set_visible(bool(window.message.get_text()))

    gear = Gtk.Button.new_from_icon_name("emblem-system-symbolic")
    gear.set_tooltip_text("Настройки")
    gear.connect("clicked", lambda *_: pages.set_visible_child_name("settings"))
    header.pack_end(gear)
    back = Gtk.Button.new_from_icon_name("go-previous-symbolic")
    back.set_tooltip_text("На главный экран")
    back.connect("clicked", lambda *_: pages.set_visible_child_name("home"))
    header.pack_start(back)
    nav = Gtk.Box(homogeneous=True, spacing=4)
    nav.set_margin_start(12)
    nav.set_margin_end(12)
    nav.set_margin_bottom(10)
    footer.append(nav)
    tabs = {}
    for name, caption in (("home", "VPN"), ("diagnostics", "Диагностика"), ("profiles", "Серверы")):
        button = Gtk.ToggleButton(label=caption)
        button.connect("clicked", lambda _button, target=name: pages.set_visible_child_name(target))
        tabs[name] = button
        nav.append(button)

    def sync(*_args):
        selected = pages.get_visible_child_name()
        for name, button in tabs.items():
            button.set_active(name == selected)
        back.set_visible(selected != "home")
        gear.set_visible(selected != "settings")
        title.set_text({"home": "Kachur VPN", "profiles": "Серверы",
                        "diagnostics": "Диагностика", "settings": "Настройки"}[selected])
    pages.connect("notify::visible-child-name", sync)
    pages.set_visible_child_name("home")
    sync()

HOOK = "        from . import mobile_ui\n        mobile_ui.apply(self)\n"
ANCHOR = "        GLib.idle_add(self.refresh)\n"


def atomic_write(target, data):
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
            temporary.chmod(target.stat().st_mode & 0o777 if target.exists() else 0o644)
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)


def install():
    import argparse
    parser = argparse.ArgumentParser(description="Главный экран Kachur VPN для Fedora")
    parser.add_argument("--undo", action="store_true", help="Вернуть прежний интерфейс")
    parser.add_argument("--root", type=Path, default=Path.home() / "Kachur-vpn")
    args = parser.parse_args()
    package = args.root / "desktop/fedora/kachur_fedora"
    app = package / "app.py"
    if not app.is_file():
        sys.exit(f"Приложение не найдено в {package}. Укажите --root и каталог проекта.")
    source = app.read_text(encoding="utf-8")
    if args.undo:
        if HOOK not in source:
            print("Используется прежний интерфейс.")
            return
        atomic_write(app, source.replace(HOOK, "", 1).encode())
        print("Вернулся прежний интерфейс. Откройте Kachur VPN заново.")
        return
    if HOOK not in source and source.count(ANCHOR) != 1:
        sys.exit("Формат установленного клиента отличается. Файлы сохранены без изменений.")
    if os.geteuid() == 0:
        sys.exit("Запустите установку от вашего обычного пользователя.")
    original = ast.parse(source)
    available = {node.attr for node in ast.walk(original) if isinstance(node, ast.Attribute)}
    needed = {"power", "status", "profile", "rx", "handshake", "import_button", "message",
              "refresh_button", "handshake_button", "offline_test_button", "connection_button",
              "bypass_summary"}
    if not needed <= available:
        sys.exit("Эта версия клиента использует другую компоновку. Обновление остановлено.")
    patched = source if HOOK in source else source.replace(ANCHOR, HOOK + ANCHOR)
    compile(patched, str(app), "exec")
    # Smoke-test the real local frontend, with scheduled callbacks still pending.
    # Do not run the main loop or connect to a VPN during installation.
    import gi
    gi.require_version("Gtk", "4.0")
    gi.require_version("Adw", "1")
    from gi.repository import Adw, Gdk, Gtk
    Gtk.init()
    Adw.init()
    if Gdk.Display.get_default() is None:
        sys.exit("Запустите команду в графическом сеансе Fedora.")
    sys.path.insert(0, str(package.parent))
    from kachur_fedora.app import MainWindow
    import kachur_fedora
    # Use the candidate in memory, including when upgrading an older installation.
    kachur_fedora.mobile_ui = sys.modules[__name__]
    probe_app = Adw.Application(application_id="com.kachur.vpn.Preview")
    probe_app.register(None)
    window = MainWindow(probe_app, auto_connect=False)
    if HOOK not in source:
        apply(window)
    window.destroy()
    module = package / "mobile_ui.py"
    atomic_write(module, Path(__file__).read_bytes())
    if patched != source:
        atomic_write(app, patched.encode())
    print("Интерфейс установлен. Закройте окно Kachur VPN и откройте его снова.")


if __name__ == "__main__":
    install()
